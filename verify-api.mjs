/** Checks for the waitlist functions against an in-memory Upstash and Resend. No network or preview needed. */
import assert from 'node:assert/strict';
import waitlist from './api/waitlist.js';
import waitlistExport from './api/waitlist-export.js';
import waitlistUnsubscribe from './api/waitlist-unsubscribe.js';
import { handleExport, handleJoin, handleUnsubscribe } from './server/waitlist/handlers.js';
import { CONSENT_VERSION } from './server/waitlist/input.js';
import { RATE_LIMIT } from './server/waitlist/store.js';

const failures = [];
let passed = 0;
async function check(label, run) {
  try {
    await run();
    passed += 1;
    console.log(`PASS ${label}`);
  } catch (error) {
    failures.push({ label, message: error.message });
    console.error(`FAIL ${label}: ${error.stack}`);
  }
}

const REDIS_URL = 'https://example-redis.upstash.io';
const SECRET = 'export-secret-for-tests-0123456789';

// Just the Redis commands the store uses, with Upstash's REST reply shapes.
function fakeUpstash() {
  const strings = new Map(), hashes = new Map(), sortedSets = new Map(), expiries = new Map();
  const run = ([command, ...args]) => {
    switch (command) {
      case 'SET': {
        const [key, value, ...options] = args;
        if (options.includes('NX') && strings.has(key)) return null;
        strings.set(key, value);
        const ex = options.indexOf('EX');
        if (ex >= 0) expiries.set(key, Number(options[ex + 1]));
        return 'OK';
      }
      case 'INCR': {
        const value = Number(strings.get(args[0]) || 0) + 1;
        strings.set(args[0], String(value));
        return value;
      }
      case 'GET': return strings.get(args[0]) ?? null;
      case 'DEL': return args.reduce((count, key) => count + Number(strings.delete(key) || hashes.delete(key) || sortedSets.delete(key)), 0);
      case 'HGET': return hashes.get(args[0])?.[args[1]] ?? null;
      case 'HSET': {
        const [key, ...pairs] = args;
        const hash = hashes.get(key) || {};
        let added = 0;
        for (let i = 0; i < pairs.length; i += 2) {
          if (!(pairs[i] in hash)) added += 1;
          hash[pairs[i]] = String(pairs[i + 1]);
        }
        hashes.set(key, hash);
        return added;
      }
      case 'HSETNX': {
        const [key, field, value] = args;
        const hash = hashes.get(key) || {};
        if (field in hash) return 0;
        hash[field] = String(value);
        hashes.set(key, hash);
        return 1;
      }
      case 'HGETALL': return Object.entries(hashes.get(args[0]) || {}).flat();
      case 'ZADD': {
        const [key, ...rest] = args;
        const nx = rest[0] === 'NX';
        const [score, member] = nx ? rest.slice(1) : rest;
        const set = sortedSets.get(key) || new Map();
        if (set.has(member) && nx) return 0;
        const added = set.has(member) ? 0 : 1;
        set.set(member, Number(score));
        sortedSets.set(key, set);
        return added;
      }
      case 'ZRANGE': return [...(sortedSets.get(args[0]) || new Map())].sort((a, b) => a[1] - b[1]).map(([member]) => member);
      case 'ZREM': return Number(sortedSets.get(args[0])?.delete(args[1]) || false);
      default: throw new Error(`Unsupported command ${command}`);
    }
  };
  return { run, strings, hashes, sortedSets, expiries };
}

function fakeNetwork({ redisDown = false } = {}) {
  const redis = fakeUpstash();
  const emails = [];
  const calls = [];
  const fetch = async (url, init) => {
    calls.push(url);
    if (url.startsWith(REDIS_URL)) {
      assert.equal(init.headers.Authorization, 'Bearer redis-token');
      if (redisDown) return new Response('unavailable', { status: 502 });
      const commands = JSON.parse(init.body);
      return Response.json(commands.map(command => ({ result: redis.run(command) })));
    }
    if (url === 'https://api.resend.com/emails') {
      emails.push({ headers: init.headers, body: JSON.parse(init.body) });
      return Response.json({ id: `email-${emails.length}` });
    }
    throw new Error(`Unexpected request to ${url}`);
  };
  return { fetch, redis, emails, calls };
}

const storeEnv = { UPSTASH_REDIS_REST_URL: REDIS_URL, UPSTASH_REDIS_REST_TOKEN: 'redis-token' };
const mailEnv = { ...storeEnv, RESEND_API_KEY: 're_test', WAITLIST_FROM_EMAIL: 'Serv60 <hello@example.com>' };
const now = new Date('2026-10-28T08:00:00Z');
const valid = { email: ' Thandi@Example.com ', firstName: 'Thandi', area: 'Sandton', vehicleMake: 'Toyota', vehicleModel: 'Corolla Cross', vehicleYear: '2021', consent: true, source: 'hero' };

function joinRequest(body, { ip = '196.25.1.1', form = false } = {}) {
  return new Request('https://serv60.example/api/waitlist', {
    method: 'POST',
    headers: { 'Content-Type': form ? 'application/x-www-form-urlencoded' : 'application/json', 'X-Forwarded-For': `${ip}, 10.0.0.1` },
    body: form ? new URLSearchParams(body).toString() : JSON.stringify(body)
  });
}
const exportRequest = secret => new Request('https://serv60.example/api/waitlist-export', { headers: secret ? { Authorization: `Bearer ${secret}` } : {} });

await check('Each API file exports a Web fetch handler', async () => {
  for (const handler of [waitlist, waitlistExport, waitlistUnsubscribe]) assert.equal(typeof handler.fetch, 'function');
});

await check('Without a configured store, sign-ups fail honestly instead of pretending to succeed', async () => {
  const network = fakeNetwork();
  const originalError = console.error;
  console.error = () => {};
  try {
    const response = await handleJoin(joinRequest(valid), { env: {}, fetch: network.fetch, now });
    assert.equal(response.status, 503);
    const body = await response.json();
    assert.equal(body.ok, false);
    assert.match(body.message, /isn’t open just yet/);
    const page = await handleJoin(joinRequest(valid, { form: true }), { env: {}, fetch: network.fetch, now });
    assert.equal(page.status, 503);
    assert.doesNotMatch(await page.text(), /on the list/);
    assert.equal(network.calls.length, 0);
  } finally {
    console.error = originalError;
  }
});

await check('Invalid input returns field messages and stores nothing', async () => {
  const network = fakeNetwork();
  const response = await handleJoin(joinRequest({ email: 'not-an-email', vehicleYear: '19', firstName: 'x'.repeat(61) }), { env: storeEnv, fetch: network.fetch, now });
  assert.equal(response.status, 422);
  const { fields } = await response.json();
  assert.deepEqual(Object.keys(fields).sort(), ['consent', 'email', 'firstName', 'vehicleYear']);
  assert.match(fields.vehicleYear, /1950 and 2027/);
  assert.equal(network.calls.length, 0);
  const empty = await (await handleJoin(joinRequest({}), { env: storeEnv, fetch: network.fetch, now })).json();
  assert.equal(empty.fields.email, 'Enter your email address.');
});

await check('Malformed, oversized and non-POST requests are refused', async () => {
  const network = fakeNetwork();
  const options = { env: storeEnv, fetch: network.fetch, now };
  const malformed = new Request('https://serv60.example/api/waitlist', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: '{' });
  assert.equal((await handleJoin(malformed, options)).status, 400);
  assert.equal((await handleJoin(joinRequest({ ...valid, area: 'x'.repeat(9000) }), options)).status, 413);
  const wrongType = new Request('https://serv60.example/api/waitlist', { method: 'POST', headers: { 'Content-Type': 'text/plain' }, body: 'hi' });
  assert.equal((await handleJoin(wrongType, options)).status, 415);
  const get = await handleJoin(new Request('https://serv60.example/api/waitlist'), options);
  assert.equal(get.status, 405);
  assert.equal(get.headers.get('Allow'), 'POST');
});

await check('A valid sign-up stores the entry, its consent record and an unsubscribe token', async () => {
  const network = fakeNetwork();
  const response = await handleJoin(joinRequest(valid), { env: storeEnv, fetch: network.fetch, now });
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { ok: true, confirmationSent: false });
  const entry = network.redis.hashes.get('serv60:waitlist:entry:thandi@example.com');
  assert.equal(entry.email, 'thandi@example.com');
  assert.equal(entry.vehicleModel, 'Corolla Cross');
  assert.equal(entry.source, 'hero');
  assert.equal(entry.consentVersion, CONSENT_VERSION);
  assert.equal(entry.consentedAt, now.toISOString());
  assert.equal(entry.createdAt, now.toISOString());
  assert.match(entry.unsubscribeToken, /^[A-Za-z0-9_-]{32}$/);
  assert.equal(network.redis.strings.get(`serv60:waitlist:unsubscribe:${entry.unsubscribeToken}`), 'thandi@example.com');
  assert.deepEqual([...network.redis.sortedSets.get('serv60:waitlist:index').keys()], ['thandi@example.com']);
  const rateKeys = [...network.redis.strings.keys()].filter(key => key.includes(':rate:'));
  assert.equal(rateKeys.length, 1);
  assert(!rateKeys[0].includes('196.25'), 'The raw IP address must not be stored');
  assert.equal(network.redis.expiries.get(rateKeys[0]), RATE_LIMIT.windowSeconds);
});

await check('The Vercel KV variable names work as well as the Upstash ones', async () => {
  const network = fakeNetwork();
  const env = { KV_REST_API_URL: `${REDIS_URL}/`, KV_REST_API_TOKEN: 'redis-token' };
  assert.equal((await handleJoin(joinRequest(valid), { env, fetch: network.fetch, now })).status, 200);
});

await check('Signing up again keeps the first date and earlier answers, and sends no second email', async () => {
  const network = fakeNetwork();
  await handleJoin(joinRequest(valid), { env: mailEnv, fetch: network.fetch, now });
  const firstToken = network.redis.hashes.get('serv60:waitlist:entry:thandi@example.com').unsubscribeToken;
  const later = new Date('2026-11-02T08:00:00Z');
  const response = await handleJoin(joinRequest({ email: 'thandi@example.com', area: 'Fourways', consent: 'yes', source: 'finale' }), { env: mailEnv, fetch: network.fetch, now: later });
  assert.deepEqual(await response.json(), { ok: true, confirmationSent: false });
  const entry = network.redis.hashes.get('serv60:waitlist:entry:thandi@example.com');
  assert.equal(entry.createdAt, now.toISOString());
  assert.equal(entry.consentedAt, later.toISOString());
  assert.equal(entry.area, 'Fourways');
  assert.equal(entry.vehicleMake, 'Toyota');
  assert.equal(entry.unsubscribeToken, firstToken);
  assert.equal(network.emails.length, 1);
});

await check('Resend sends one confirmation with a one-click unsubscribe link', async () => {
  const network = fakeNetwork();
  const response = await handleJoin(joinRequest(valid), { env: { ...mailEnv, WAITLIST_SITE_URL: 'https://serv60.co.example/' }, fetch: network.fetch, now });
  assert.deepEqual(await response.json(), { ok: true, confirmationSent: true });
  assert.equal(network.emails.length, 1);
  const [{ headers, body }] = network.emails;
  const token = network.redis.hashes.get('serv60:waitlist:entry:thandi@example.com').unsubscribeToken;
  const link = `https://serv60.co.example/api/waitlist-unsubscribe?token=${token}`;
  assert.deepEqual(body.to, ['thandi@example.com']);
  assert.equal(body.from, 'Serv60 <hello@example.com>');
  assert.equal(body.headers['List-Unsubscribe'], `<${link}>`);
  assert.equal(body.headers['List-Unsubscribe-Post'], 'List-Unsubscribe=One-Click');
  assert(body.text.includes(link) && body.html.includes(link));
  assert.match(body.text, /Hi Thandi,/);
  assert.doesNotMatch(`${body.text} ${body.subject}`, /R250|money back|guarantee/i, 'The guarantee is not advertised before the soft launch');
  assert.match(headers['Idempotency-Key'], /^waitlist-confirmation-[0-9a-f]{32}$/);
  assert(!headers['Idempotency-Key'].includes('thandi'));
});

await check('A failed confirmation email still keeps the sign-up', async () => {
  const network = fakeNetwork();
  const failingMail = async (url, init) => url.includes('resend') ? new Response('{}', { status: 500 }) : network.fetch(url, init);
  const originalError = console.error;
  console.error = () => {};
  try {
    const response = await handleJoin(joinRequest(valid), { env: mailEnv, fetch: failingMail, now });
    assert.deepEqual(await response.json(), { ok: true, confirmationSent: false });
    assert(network.redis.hashes.has('serv60:waitlist:entry:thandi@example.com'));
  } finally {
    console.error = originalError;
  }
});

await check('A store outage is reported as a failure, never as success', async () => {
  const network = fakeNetwork({ redisDown: true });
  const originalError = console.error;
  console.error = () => {};
  try {
    const response = await handleJoin(joinRequest(valid), { env: storeEnv, fetch: network.fetch, now });
    assert.equal(response.status, 503);
    assert.match((await response.json()).message, /couldn’t save/);
  } finally {
    console.error = originalError;
  }
});

await check(`Rate limiting allows ${RATE_LIMIT.attempts} sign-ups per IP per window`, async () => {
  const network = fakeNetwork();
  const options = { env: storeEnv, fetch: network.fetch, now };
  for (let i = 0; i < RATE_LIMIT.attempts; i++) {
    assert.equal((await handleJoin(joinRequest({ ...valid, email: `person${i}@example.com` }), options)).status, 200);
  }
  const blocked = await handleJoin(joinRequest({ ...valid, email: 'one-more@example.com' }), options);
  assert.equal(blocked.status, 429);
  assert.equal(blocked.headers.get('Retry-After'), String(RATE_LIMIT.windowSeconds));
  assert.equal(network.redis.hashes.has('serv60:waitlist:entry:one-more@example.com'), false);
  assert.equal((await handleJoin(joinRequest({ ...valid, email: 'elsewhere@example.com' }, { ip: '41.0.0.9' }), options)).status, 200);
});

await check('A form post without JavaScript gets a readable page back', async () => {
  const network = fakeNetwork();
  const options = { env: storeEnv, fetch: network.fetch, now };
  const joined = await handleJoin(joinRequest({ email: 'pieter@example.com', consent: 'yes', source: 'page' }, { form: true }), options);
  assert.equal(joined.status, 200);
  assert.match(joined.headers.get('Content-Type'), /text\/html/);
  const html = await joined.text();
  assert.match(html, /You’re on the list\./);
  assert.match(html, /pieter@example\.com/);
  const invalid = await handleJoin(joinRequest({ email: '<script>@x', firstName: '' }, { form: true }), options);
  assert.equal(invalid.status, 422);
  const invalidHtml = await invalid.text();
  assert.match(invalidHtml, /Tick the box/);
  assert.doesNotMatch(invalidHtml, /<script>/);
});

await check('Export requires the secret and returns safe CSV', async () => {
  const network = fakeNetwork();
  const options = { env: { ...storeEnv, WAITLIST_EXPORT_SECRET: SECRET }, fetch: network.fetch, now };
  await handleJoin(joinRequest(valid), options);
  await handleJoin(joinRequest({ email: 'formula@example.com', firstName: '=HYPERLINK("x")', consent: true }), { ...options, now: new Date('2026-10-29T08:00:00Z') });
  assert.equal((await handleExport(exportRequest(), options)).status, 401);
  assert.equal((await handleExport(exportRequest('wrong-secret-wrong-secret-wrong'), options)).status, 401);
  assert.equal((await handleExport(exportRequest(SECRET), { ...options, env: { ...storeEnv, WAITLIST_EXPORT_SECRET: 'short' } })).status, 503);
  const response = await handleExport(exportRequest(SECRET), options);
  assert.equal(response.status, 200);
  assert.match(response.headers.get('Content-Disposition'), /serv60-waitlist-2026-10-28\.csv/);
  const lines = (await response.text()).replace(/^﻿/, '').trim().split('\r\n');
  assert.equal(lines.length, 3);
  assert.match(lines[0], /^"created_at","email","first_name",.*"unsubscribe_url"$/);
  assert.match(lines[1], /"thandi@example\.com","Thandi","Sandton","Toyota","Corolla Cross","2021","hero"/);
  assert.match(lines[1], /"https:\/\/serv60\.example\/api\/waitlist-unsubscribe\?token=[A-Za-z0-9_-]{32}"$/);
  assert.match(lines[2], /"'=HYPERLINK\(""x""\)"/);
});

await check('Unsubscribe asks first, then deletes every trace of the entry', async () => {
  const network = fakeNetwork();
  const options = { env: storeEnv, fetch: network.fetch, now };
  await handleJoin(joinRequest(valid), options);
  const token = network.redis.hashes.get('serv60:waitlist:entry:thandi@example.com').unsubscribeToken;
  const link = `https://serv60.example/api/waitlist-unsubscribe?token=${token}`;
  const confirm = await handleUnsubscribe(new Request(link), options);
  const confirmHtml = await confirm.text();
  assert.equal(confirm.status, 200);
  assert.match(confirmHtml, /Leave the waitlist\?/);
  assert.match(confirmHtml, /th••••@example\.com/);
  assert(network.redis.hashes.has('serv60:waitlist:entry:thandi@example.com'), 'Opening the link must not unsubscribe');
  // Mail apps send exactly this for one-click unsubscribe.
  const oneClick = await handleUnsubscribe(new Request(link, { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: 'List-Unsubscribe=One-Click' }), options);
  assert.equal(oneClick.status, 200);
  assert.match(await oneClick.text(), /You’ve left the waitlist\./);
  assert.equal(network.redis.hashes.has('serv60:waitlist:entry:thandi@example.com'), false);
  assert.equal(network.redis.sortedSets.get('serv60:waitlist:index').size, 0);
  assert.equal(network.redis.strings.has(`serv60:waitlist:unsubscribe:${token}`), false);
  const again = await handleUnsubscribe(new Request(link, { method: 'POST' }), options);
  assert.match(await again.text(), /You’re not on the list\./);
  const forged = await handleUnsubscribe(new Request('https://serv60.example/api/waitlist-unsubscribe?token=../../etc'), options);
  assert.match(await forged.text(), /You’re not on the list\./);
});

console.log(JSON.stringify({ passed, failed: failures.length, failures }, null, 2));
if (failures.length) process.exitCode = 1;
