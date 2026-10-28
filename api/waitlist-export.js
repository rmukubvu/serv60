/** GET /api/waitlist-export: the whole waitlist as CSV, for the founders only. See README, "Waitlist". */
import { handleExport } from '../server/waitlist/handlers.js';

export default { fetch: request => handleExport(request) };
