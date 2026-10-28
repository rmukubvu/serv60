/**
 * The waitlist lives in Upstash Redis, reached through its REST API with plain fetch, so the
 * functions need no npm packages (vercel.json deliberately skips installing dependencies).
 *
 * Keys:
 *   serv60:waitlist:entry:<email>        hash of one person's details and consent record
 *   serv60:waitlist:index                sorted set of emails, scored by first sign-up time
 *   serv60:waitlist:unsubscribe:<token>  email for an unsubscribe link, so links never carry the address
 *   serv60:waitlist:rate:<client>        sign-up attempts from one IP (keyed by HMAC), expiring
 */
import { createHmac, randomBytes } from 'node:crypto';

const PREFIX = 'serv60:waitlist';
const keys = {
  entry: email => `${PREFIX}:entry:${email}`,
  index: `${PREFIX}:index`,
  unsubscribe: token => `${PREFIX}:unsubscribe:${token}`,
  rate: client => `${PREFIX}:rate:${client}`
};
export const RATE_LIMIT = { attempts: 8, windowSeconds: 600 };
const OPTIONAL_FIELDS = ['firstName', 'area', 'vehicleMake', 'vehicleModel', 'vehicleYear'];
const TOKEN = /^[A-Za-z0-9_-]{32}$/;
const EXPORT_BATCH = 200;

export class StoreUnavailable extends Error {}

/** Returns null when no store is configured, so callers can refuse sign-ups honestly. */
export function storeFromEnv(env = process.env, fetchImpl = globalThis.fetch) {
  // The Vercel Marketplace integration may name the variables either way.
  const url = env.UPSTASH_REDIS_REST_URL || env.KV_REST_API_URL;
  const token = env.UPSTASH_REDIS_REST_TOKEN || env.KV_REST_API_TOKEN;
  if (!url || !token) return null;
  return createStore({ url: url.replace(/\/+$/, ''), token, fetch: fetchImpl });
}

export function isUnsubscribeToken(value) {
  return typeof value === 'string' && TOKEN.test(value);
}

function createStore({ url, token, fetch }) {
  async function send(path, commands) {
    let response;
    try {
      response = await fetch(`${url}${path}`, {
        method: 'POST',
        headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
        body: JSON.stringify(commands),
        signal: AbortSignal.timeout(6000)
      });
    } catch (error) {
      throw new StoreUnavailable(`Upstash request failed (${error.name})`);
    }
    if (!response.ok) throw new StoreUnavailable(`Upstash responded with ${response.status}`);
    const replies = await response.json();
    // A discarded transaction comes back as one error object instead of a list of replies.
    if (!Array.isArray(replies)) throw new StoreUnavailable(`Upstash refused the request: ${replies?.error}`);
    return replies.map(reply => {
      if (reply?.error) throw new StoreUnavailable(`Upstash command failed: ${reply.error}`);
      return reply?.result ?? null;
    });
  }
  const pipeline = commands => send('/pipeline', commands);
  const transaction = commands => send('/multi-exec', commands);
  // The raw IP never reaches the store; the token doubles as the HMAC key.
  const clientKey = ip => keys.rate(createHmac('sha256', token).update(ip).digest('base64url').slice(0, 24));

  return {
    /**
     * Adds or refreshes one entry. Returns `{ status: 'joined' | 'already-joined', unsubscribeToken }`
     * or `{ status: 'rate-limited', retryAfter }`.
     */
    async join(entry, { ip, consentVersion, now = new Date() }) {
      const entryKey = keys.entry(entry.email);
      const rateKey = clientKey(ip || 'unknown');
      const [, attempts, existingToken] = await pipeline([
        ['SET', rateKey, '0', 'EX', String(RATE_LIMIT.windowSeconds), 'NX'],
        ['INCR', rateKey],
        ['HGET', entryKey, 'unsubscribeToken']
      ]);
      if (Number(attempts) > RATE_LIMIT.attempts) return { status: 'rate-limited', retryAfter: RATE_LIMIT.windowSeconds };
      const unsubscribeToken = isUnsubscribeToken(existingToken) ? existingToken : randomBytes(24).toString('base64url');
      const timestamp = now.toISOString();
      // A repeat sign-up refreshes consent, and only the details given this time; blanks never erase earlier answers.
      const fields = { email: entry.email, source: entry.source, consentVersion, consentedAt: timestamp, updatedAt: timestamp, unsubscribeToken };
      for (const field of OPTIONAL_FIELDS) if (entry[field]) fields[field] = entry[field];
      const [, , added] = await transaction([
        ['HSET', entryKey, ...Object.entries(fields).flat()],
        ['HSETNX', entryKey, 'createdAt', timestamp],
        ['ZADD', keys.index, 'NX', String(now.getTime()), entry.email],
        ['SET', keys.unsubscribe(unsubscribeToken), entry.email]
      ]);
      return { status: Number(added) === 1 ? 'joined' : 'already-joined', unsubscribeToken };
    },

    /** Every entry, oldest first, as plain objects. */
    async list() {
      const [emails] = await pipeline([['ZRANGE', keys.index, '0', '-1']]);
      const entries = [];
      for (let start = 0; start < (emails || []).length; start += EXPORT_BATCH) {
        const batch = emails.slice(start, start + EXPORT_BATCH);
        const hashes = await pipeline(batch.map(email => ['HGETALL', keys.entry(email)]));
        for (const hash of hashes) {
          const entry = toObject(hash);
          if (entry.email) entries.push(entry);
        }
      }
      return entries;
    },

    async emailForUnsubscribeToken(unsubscribeToken) {
      if (!isUnsubscribeToken(unsubscribeToken)) return null;
      const [email] = await pipeline([['GET', keys.unsubscribe(unsubscribeToken)]]);
      return email || null;
    },

    /** Deletes the person's details entirely. Returns the email that was removed, or null. */
    async unsubscribe(unsubscribeToken) {
      const email = await this.emailForUnsubscribeToken(unsubscribeToken);
      if (!email) return null;
      const [storedToken] = await pipeline([['HGET', keys.entry(email), 'unsubscribeToken']]);
      const commands = [
        ['DEL', keys.entry(email)],
        ['ZREM', keys.index, email],
        ['DEL', keys.unsubscribe(unsubscribeToken)]
      ];
      if (isUnsubscribeToken(storedToken) && storedToken !== unsubscribeToken) commands.push(['DEL', keys.unsubscribe(storedToken)]);
      await transaction(commands);
      return email;
    }
  };
}

// HGETALL arrives as a flat [field, value, …] list over REST.
function toObject(hash) {
  if (!hash) return {};
  if (!Array.isArray(hash)) return hash;
  const object = {};
  for (let i = 0; i < hash.length; i += 2) object[hash[i]] = hash[i + 1];
  return object;
}
