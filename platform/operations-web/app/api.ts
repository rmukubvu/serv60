import { API_BASE } from './config';
import { clearOpsSession, opsAuthHeaders } from './ops-auth';

/**
 * Every call to the platform API goes through here, so each one carries the staff token.
 * A 401 means the token is missing or expired: clear the session and send staff back to login.
 */
export async function apiFetch(path: string, init: RequestInit = {}): Promise<Response> {
  const response = await fetch(`${API_BASE}${path}`, {
    ...init,
    headers: { ...opsAuthHeaders(), ...(init.headers ?? {}) },
  });
  if (response.status === 401 && typeof window !== 'undefined') {
    clearOpsSession();
    window.location.assign(`/login?next=${encodeURIComponent(window.location.pathname)}`);
  }
  return response;
}
