/** GET and POST /api/waitlist-unsubscribe: the link in every waitlist email. See README, "Waitlist". */
import { handleUnsubscribe } from '../server/waitlist/handlers.js';

export default { fetch: request => handleUnsubscribe(request) };
