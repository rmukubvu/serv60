/**
 * Request handlers behind api/waitlist*.js. They take Web Requests and return Web Responses;
 * `env` and `fetch` are injectable so verify-api.mjs can run them without a network.
 */
import { createHash, timingSafeEqual } from 'node:crypto';
import { CONSENT_VERSION, validateEntry } from './input.js';
import { mailerFromEnv } from './email.js';
import { escapeHtml, htmlPage } from './pages.js';
import { StoreUnavailable, storeFromEnv } from './store.js';

const MAX_BODY_BYTES = 8 * 1024;
const MESSAGES = {
  notConfigured: 'The waitlist isn’t open just yet. Please try again soon.',
  storeFailed: 'We couldn’t save your details just now. Please try again in a few minutes.',
  rateLimited: 'There have been a lot of sign-ups from your network just now. Please try again in a few minutes.'
};

function json(status, body, headers = {}) {
  return Response.json(body, { status, headers: { 'Cache-Control': 'no-store', ...headers } });
}

function plain(status, body, headers = {}) {
  return new Response(`${body}\n`, { status, headers: { 'Content-Type': 'text/plain; charset=utf-8', 'Cache-Control': 'no-store', ...headers } });
}

// Vercel overwrites x-forwarded-for with the client's address, so visitors cannot spoof it there.
function clientIp(request) {
  return (request.headers.get('x-forwarded-for') || '').split(',')[0].trim() || request.headers.get('x-real-ip') || 'unknown';
}

function siteUrl(request, env) {
  return (env.WAITLIST_SITE_URL || new URL(request.url).origin).replace(/\/+$/, '');
}

function unsubscribeUrl(request, env, token) {
  return `${siteUrl(request, env)}/api/waitlist-unsubscribe?token=${encodeURIComponent(token)}`;
}

// JavaScript sends JSON and reads JSON back; the no-JavaScript form posts and gets a page.
const jsonReplies = {
  invalid: fields => json(422, { ok: false, error: 'invalid', message: 'Please check the highlighted details.', fields }),
  unavailable: message => json(503, { ok: false, error: 'unavailable', message }),
  rateLimited: retryAfter => json(429, { ok: false, error: 'rate-limited', message: MESSAGES.rateLimited }, { 'Retry-After': String(retryAfter) }),
  joined: ({ confirmationSent }) => json(200, { ok: true, confirmationSent })
};
const backToForm = '<p class="page-actions"><a class="pill-button" href="/waitlist.html">Back to the form <span aria-hidden="true">↗</span></a></p>';
const pageReplies = {
  invalid: fields => htmlPage({
    status: 422, title: 'Check your details', eyebrow: 'JOIN THE WAITLIST', heading: 'Almost there.',
    body: `<p>Please fix the following, then send the form again:</p><ul class="page-errors">${Object.values(fields).map(message => `<li>${escapeHtml(message)}</li>`).join('')}</ul>${backToForm}<p class="page-note">Your browser’s back button keeps what you typed.</p>`
  }),
  unavailable: message => htmlPage({ status: 503, title: 'Waitlist unavailable', eyebrow: 'JOIN THE WAITLIST', heading: 'Not just yet.', body: `<p>${escapeHtml(message)}</p>${backToForm}` }),
  rateLimited: retryAfter => htmlPage({ status: 429, title: 'Please try again soon', eyebrow: 'JOIN THE WAITLIST', heading: 'One moment.', body: `<p>${escapeHtml(MESSAGES.rateLimited)}</p>${backToForm}`, headers: { 'Retry-After': String(retryAfter) } }),
  joined: ({ entry, confirmationSent }) => htmlPage({
    title: 'You’re on the list', eyebrow: 'WELCOME TO THE WAITLIST', heading: 'You’re on the list.',
    body: `<p>We’ll email <strong>${escapeHtml(entry.email)}</strong> when Serv60 opens, and waitlist members get early access to the app first.</p>${confirmationSent ? '<p>A confirmation is on its way to your inbox.</p>' : ''}<p class="page-actions"><a class="pill-button" href="/">Back to the vision <span aria-hidden="true">↗</span></a></p>`
  })
};

/** POST /api/waitlist */
export async function handleJoin(request, { env = process.env, fetch = globalThis.fetch, now = new Date() } = {}) {
  if (request.method !== 'POST') return json(405, { ok: false, error: 'method-not-allowed', message: 'Send the form to join the waitlist.' }, { Allow: 'POST' });
  const contentType = (request.headers.get('content-type') || '').split(';')[0].trim().toLowerCase();
  const fromForm = contentType === 'application/x-www-form-urlencoded';
  if (!fromForm && contentType !== 'application/json') return json(415, { ok: false, error: 'unsupported', message: 'Send the form as JSON or as a regular form post.' });
  if (Number(request.headers.get('content-length')) > MAX_BODY_BYTES) return json(413, { ok: false, error: 'too-large', message: 'That is more than the form needs.' });
  const body = await request.text();
  if (body.length > MAX_BODY_BYTES) return json(413, { ok: false, error: 'too-large', message: 'That is more than the form needs.' });
  let raw;
  try {
    raw = fromForm ? Object.fromEntries(new URLSearchParams(body)) : JSON.parse(body);
  } catch {
    return json(400, { ok: false, error: 'malformed', message: 'We couldn’t read that form. Please try again.' });
  }
  const reply = fromForm ? pageReplies : jsonReplies;
  const { entry, errors } = validateEntry(raw, now);
  if (Object.keys(errors).length) return reply.invalid(errors);

  const store = storeFromEnv(env, fetch);
  if (!store) {
    console.error('Waitlist store is not configured: set UPSTASH_REDIS_REST_URL and UPSTASH_REDIS_REST_TOKEN (or KV_REST_API_URL and KV_REST_API_TOKEN).');
    return reply.unavailable(MESSAGES.notConfigured);
  }
  let result;
  try {
    result = await store.join(entry, { ip: clientIp(request), consentVersion: CONSENT_VERSION, now });
  } catch (error) {
    if (!(error instanceof StoreUnavailable)) throw error;
    console.error(error.message);
    return reply.unavailable(MESSAGES.storeFailed);
  }
  if (result.status === 'rate-limited') return reply.rateLimited(result.retryAfter);
  // Only a first sign-up is confirmed, so the form cannot be used to flood someone's inbox.
  // A repeat gets the same answer as a first sign-up, which keeps membership private.
  const mailer = result.status === 'joined' ? mailerFromEnv(env, fetch) : null;
  const confirmationSent = mailer
    ? await mailer.sendConfirmation({ to: entry.email, firstName: entry.firstName, unsubscribeUrl: unsubscribeUrl(request, env, result.unsubscribeToken) })
    : false;
  return reply.joined({ entry, confirmationSent });
}

// Compare digests so neither the content nor the length of the secret leaks through timing.
function sameSecret(supplied, expected) {
  const digest = value => createHash('sha256').update(value).digest();
  return timingSafeEqual(digest(supplied), digest(expected));
}

// Spreadsheet apps run cells that start with these characters as formulas.
function csvCell(value) {
  let text = value == null ? '' : String(value);
  if (/^[=+\-@\t\r]/.test(text)) text = `'${text}`;
  return `"${text.replace(/"/g, '""')}"`;
}

const CSV_COLUMNS = [
  ['created_at', entry => entry.createdAt], ['email', entry => entry.email], ['first_name', entry => entry.firstName],
  ['area', entry => entry.area], ['vehicle_make', entry => entry.vehicleMake], ['vehicle_model', entry => entry.vehicleModel],
  ['vehicle_year', entry => entry.vehicleYear], ['source', entry => entry.source], ['consent_version', entry => entry.consentVersion],
  ['consented_at', entry => entry.consentedAt], ['updated_at', entry => entry.updatedAt]
];

/** GET /api/waitlist-export with `Authorization: Bearer <WAITLIST_EXPORT_SECRET>` */
export async function handleExport(request, { env = process.env, fetch = globalThis.fetch, now = new Date() } = {}) {
  if (request.method !== 'GET') return plain(405, 'Use GET to export the waitlist.', { Allow: 'GET' });
  const secret = env.WAITLIST_EXPORT_SECRET || '';
  if (secret.length < 24) return plain(503, 'Export is not configured. Set WAITLIST_EXPORT_SECRET to a random value of at least 24 characters.');
  const supplied = (request.headers.get('authorization') || '').replace(/^Bearer\s+/i, '');
  if (!supplied || !sameSecret(supplied, secret)) return plain(401, 'Unauthorised.', { 'WWW-Authenticate': 'Bearer' });
  const store = storeFromEnv(env, fetch);
  if (!store) return plain(503, 'The waitlist store is not configured.');
  let entries;
  try {
    entries = await store.list();
  } catch (error) {
    if (!(error instanceof StoreUnavailable)) throw error;
    console.error(error.message);
    return plain(503, 'The waitlist store did not respond. Try again in a minute.');
  }
  const base = siteUrl(request, env);
  const header = [...CSV_COLUMNS.map(([name]) => name), 'unsubscribe_url'].map(csvCell).join(',');
  const rows = entries.map(entry => [
    ...CSV_COLUMNS.map(([, read]) => read(entry)),
    entry.unsubscribeToken ? `${base}/api/waitlist-unsubscribe?token=${entry.unsubscribeToken}` : ''
  ].map(csvCell).join(','));
  // The byte-order mark makes Excel read names with accents as UTF-8.
  return new Response(`﻿${[header, ...rows].join('\r\n')}\r\n`, {
    headers: {
      'Content-Type': 'text/csv; charset=utf-8',
      'Content-Disposition': `attachment; filename="serv60-waitlist-${now.toISOString().slice(0, 10)}.csv"`,
      'Cache-Control': 'no-store'
    }
  });
}

// Enough to recognise your own address if a link is forwarded, without revealing it.
function maskEmail(email) {
  const [name, domain] = email.split('@');
  return `${name.slice(0, 2)}${'•'.repeat(Math.max(1, Math.min(6, name.length - 2)))}@${domain}`;
}

const goneLink = () => htmlPage({
  title: 'Not on the list', eyebrow: 'SERV60 WAITLIST', heading: 'You’re not on the list.',
  body: '<p>This link has already been used, or your details were already deleted. Either way, the Serv60 waitlist holds nothing for this address.</p><p class="page-actions"><a class="pill-button" href="/">Back to the vision <span aria-hidden="true">↗</span></a></p>'
});
const storeDown = () => htmlPage({ status: 503, title: 'Please try again', eyebrow: 'SERV60 WAITLIST', heading: 'Please try again.', body: '<p>We couldn’t update the waitlist just now. Please use the link again in a few minutes.</p>' });

/**
 * GET shows a confirmation button, because mail scanners open links on their own.
 * POST deletes the entry; mail apps also POST here for one-click unsubscribe (RFC 8058).
 */
export async function handleUnsubscribe(request, { env = process.env, fetch = globalThis.fetch } = {}) {
  if (!['GET', 'POST'].includes(request.method)) return plain(405, 'Use the link in your email.', { Allow: 'GET, POST' });
  const store = storeFromEnv(env, fetch);
  if (!store) return storeDown();
  const token = new URL(request.url).searchParams.get('token') || '';
  try {
    if (request.method === 'GET') {
      const email = await store.emailForUnsubscribeToken(token);
      if (!email) return goneLink();
      return htmlPage({
        title: 'Leave the waitlist', eyebrow: 'SERV60 WAITLIST', heading: 'Leave the waitlist?',
        body: `<p>We’ll stop emailing <strong>${escapeHtml(maskEmail(email))}</strong> and delete your details from the Serv60 waitlist.</p><form class="page-actions" method="post" action="/api/waitlist-unsubscribe?token=${encodeURIComponent(token)}"><button class="pill-button" type="submit">Unsubscribe and delete my details <span aria-hidden="true">↗</span></button></form>`
      });
    }
    const removed = await store.unsubscribe(token);
    if (!removed) return goneLink();
    return htmlPage({
      title: 'You’ve left the waitlist', eyebrow: 'SERV60 WAITLIST', heading: 'You’ve left the waitlist.',
      body: '<p>Your details have been deleted and we won’t email you about the launch. You’re welcome back any time.</p><p class="page-actions"><a class="pill-button" href="/">Back to the vision <span aria-hidden="true">↗</span></a></p>'
    });
  } catch (error) {
    if (!(error instanceof StoreUnavailable)) throw error;
    console.error(error.message);
    return storeDown();
  }
}
