import { afterEach, beforeEach, expect, it, vi } from 'vitest';
import { apiFetch } from '../app/api';

const assign = vi.fn();

// An in-memory store, so the test does not depend on the runtime's localStorage support.
function memoryStorage(): Storage {
  const items = new Map<string, string>();
  return {
    get length() { return items.size; },
    clear: () => items.clear(),
    getItem: (key) => items.get(key) ?? null,
    key: (index) => [...items.keys()][index] ?? null,
    removeItem: (key) => { items.delete(key); },
    setItem: (key, value) => { items.set(key, String(value)); },
  };
}

beforeEach(() => {
  vi.stubGlobal('localStorage', memoryStorage());
  vi.stubGlobal('location', { ...window.location, assign, pathname: '/settings' });
});

afterEach(() => {
  vi.unstubAllGlobals();
  assign.mockReset();
});

it('sends the stored staff token with every API call', async () => {
  window.localStorage.setItem('serv60.ops.token', 'staff-token');
  const fetcher = vi.fn().mockResolvedValue({ status: 200, ok: true });
  vi.stubGlobal('fetch', fetcher);

  await apiFetch('/v1/hubs/h/settings', { method: 'PUT', headers: { 'Content-Type': 'application/json' } });

  expect(fetcher.mock.calls[0][1].headers).toEqual({
    Authorization: 'Bearer staff-token',
    'Content-Type': 'application/json',
  });
});

it('clears the session and returns to login when the token is rejected', async () => {
  window.localStorage.setItem('serv60.ops.token', 'expired');
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ status: 401, ok: false }));

  await apiFetch('/v1/hubs/h/settings');

  expect(window.localStorage.getItem('serv60.ops.token')).toBeNull();
  expect(assign).toHaveBeenCalledWith('/login?next=%2Fsettings');
});
