/** What a visitor may send to the waitlist, normalised and checked before anything is stored. */

// Bump the version whenever the consent wording changes, so every entry records what was agreed to.
export const CONSENT_VERSION = '2026-10-28';
export const CONSENT_TEXT = 'Serv60 may use these details to tell me when it opens and to offer me early access to the app. Serv60 won’t sell or share them, and I can unsubscribe at any time.';

export const LIMITS = { email: 254, firstName: 60, area: 80, vehicleMake: 40, vehicleModel: 60 };
export const EARLIEST_VEHICLE_YEAR = 1950;
const SOURCES = new Set(['nav', 'hero', 'experience', 'planner', 'finale', 'faq', 'page']);
const EMAIL = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const ACCEPTED = new Set(['yes', 'on', 'true', '1']);

// Collapse whitespace and drop control characters, which have no place in a name or suburb.
function clean(value) {
  if (typeof value !== 'string' && typeof value !== 'number') return '';
  return String(value).replace(/[\u0000-\u001f\u007f]/g, ' ').replace(/\s+/g, ' ').trim();
}

/** Returns `{ entry, errors }`; `errors` maps field names to messages a visitor can act on. */
export function validateEntry(raw, now = new Date()) {
  const input = raw && typeof raw === 'object' ? raw : {};
  const errors = {};
  const entry = {
    email: clean(input.email).toLowerCase(),
    firstName: clean(input.firstName),
    area: clean(input.area),
    vehicleMake: clean(input.vehicleMake),
    vehicleModel: clean(input.vehicleModel),
    vehicleYear: clean(input.vehicleYear),
    source: SOURCES.has(clean(input.source)) ? clean(input.source) : 'unknown'
  };
  if (!entry.email) errors.email = 'Enter your email address.';
  else if (entry.email.length > LIMITS.email || !EMAIL.test(entry.email)) errors.email = 'Enter an email address like name@example.com.';
  for (const field of ['firstName', 'area', 'vehicleMake', 'vehicleModel']) {
    if (entry[field].length > LIMITS[field]) errors[field] = `Keep this to ${LIMITS[field]} characters or fewer.`;
  }
  const latestYear = now.getFullYear() + 1;
  if (entry.vehicleYear) {
    const year = Number(entry.vehicleYear);
    if (!/^\d{4}$/.test(entry.vehicleYear) || year < EARLIEST_VEHICLE_YEAR || year > latestYear) {
      errors.vehicleYear = `Enter a year between ${EARLIEST_VEHICLE_YEAR} and ${latestYear}.`;
    }
  }
  const consent = input.consent === true || ACCEPTED.has(clean(input.consent).toLowerCase());
  if (!consent) errors.consent = 'Tick the box so we can email you about the launch.';
  return { entry, errors };
}
