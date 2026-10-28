/** POST /api/waitlist: adds a visitor to the launch waitlist. See README, "Waitlist". */
import { handleJoin } from '../server/waitlist/handlers.js';

export default { fetch: request => handleJoin(request) };
