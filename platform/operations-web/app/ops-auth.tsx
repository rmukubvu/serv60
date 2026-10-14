'use client';

import { usePathname, useRouter } from 'next/navigation';
import {
  createContext,
  type ReactNode,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
} from 'react';
import { API_BASE, DEMO_MODE, HUB_ID } from './config';
import { Button } from './ui/button';

const SESSION_KEY = 'serv60.ops.session';
const TOKEN_KEY = 'serv60.ops.token';

/** localStorage can be missing or throw (private browsing, blocked storage); never crash on it. */
function storage(): Storage | null {
  try {
    const store = typeof window === 'undefined' ? null : window.localStorage;
    return store && typeof store.getItem === 'function' ? store : null;
  } catch {
    return null;
  }
}

export const AUTH0_LOGIN_URL =
  process.env.NEXT_PUBLIC_AUTH0_LOGIN_URL ?? '/auth/login';

export type OpsSession = {
  email: string;
  method: 'staff-email' | 'auth0' | 'token';
  signedInAt: string;
};

type OpsAuthContextValue = {
  session: OpsSession | null;
  ready: boolean;
  signOut: () => void;
  refresh: () => void;
};

const OpsAuthContext = createContext<OpsAuthContextValue | null>(null);

export function isAuth0Configured(): boolean {
  return Boolean(
    process.env.NEXT_PUBLIC_AUTH0_DOMAIN && process.env.NEXT_PUBLIC_AUTH0_CLIENT_ID,
  );
}

export function readOpsSession(): OpsSession | null {
  try {
    const raw = storage()?.getItem(SESSION_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as OpsSession;
    if (!parsed?.email) return null;
    return parsed;
  } catch {
    return null;
  }
}

/** Signed in means holding a staff token; an email alone is enough only in local demo mode. */
export function isOpsSignedIn(): boolean {
  return Boolean(opsBearerToken()) || (DEMO_MODE && readOpsSession() != null);
}

export function signInOpsSession(session: Omit<OpsSession, 'signedInAt'>): void {
  const value: OpsSession = { ...session, signedInAt: new Date().toISOString() };
  storage()?.setItem(SESSION_KEY, JSON.stringify(value));
  // Same-tab storage events do not fire — notify the in-memory provider.
  window.dispatchEvent(new Event('serv60-ops-auth'));
}

function opsBearerToken(): string {
  return storage()?.getItem(TOKEN_KEY) ?? '';
}

export function clearOpsSession(): void {
  storage()?.removeItem(SESSION_KEY);
  storage()?.removeItem(TOKEN_KEY);
  window.dispatchEvent(new Event('serv60-ops-auth'));
}

/**
 * Accepts a staff access token only after the API confirms it: the token must authenticate and
 * belong to an operator of this hub. Nothing is stored for a token the API rejects.
 */
export async function signInWithToken(rawToken: string): Promise<void> {
  const token = rawToken.trim().replace(/^Bearer\s+/i, '');
  if (!token) throw new Error('Paste your staff access token to continue.');
  const response = await fetch(`${API_BASE}/v1/hubs/${HUB_ID}/settings`, {
    headers: { Authorization: `Bearer ${token}` },
  });
  if (response.status === 401) throw new Error('That token is not valid or has expired.');
  if (response.status === 403) throw new Error('That token is not an operator of this hub.');
  if (!response.ok) throw new Error(`Could not verify the token (${response.status}).`);
  const store = storage();
  if (!store) throw new Error('This browser is blocking storage, so the session cannot be kept.');
  store.setItem(TOKEN_KEY, token);
  signInOpsSession({ email: 'Staff token', method: 'token' });
}

export function opsAuthHeaders(): HeadersInit {
  const token = opsBearerToken();
  return token ? { Authorization: `Bearer ${token}` } : {};
}

export function OpsAuthProvider({ children }: { children: ReactNode }) {
  const [session, setSession] = useState<OpsSession | null>(null);
  const [ready, setReady] = useState(false);

  const refresh = useCallback(() => {
    setSession(readOpsSession());
    setReady(true);
  }, []);

  useEffect(() => {
    refresh();
    const onStorage = (event: StorageEvent) => {
      if (event.key === SESSION_KEY || event.key === TOKEN_KEY) refresh();
    };
    const onAuth = () => refresh();
    window.addEventListener('storage', onStorage);
    window.addEventListener('serv60-ops-auth', onAuth);
    return () => {
      window.removeEventListener('storage', onStorage);
      window.removeEventListener('serv60-ops-auth', onAuth);
    };
  }, [refresh]);

  const signOut = useCallback(() => {
    setSession(null);
    clearOpsSession();
  }, []);

  const value = useMemo(
    () => ({ session, ready, signOut, refresh }),
    [session, ready, signOut, refresh],
  );

  return <OpsAuthContext.Provider value={value}>{children}</OpsAuthContext.Provider>;
}

export function useOpsAuth(): OpsAuthContextValue {
  const context = useContext(OpsAuthContext);
  if (!context) throw new Error('useOpsAuth requires OpsAuthProvider');
  return context;
}

/** Redirects anonymous staff to the branded login experience. */
export function OpsAuthGate({ children }: { children: ReactNode }) {
  const router = useRouter();
  const pathname = usePathname();
  const { session, ready } = useOpsAuth();
  const signedIn = (session != null && (DEMO_MODE || Boolean(opsBearerToken()))) || isOpsSignedIn();

  useEffect(() => {
    if (!ready) return;
    if (!signedIn) {
      const next = encodeURIComponent(pathname || '/');
      router.replace(`/login?next=${next}`);
    }
  }, [ready, signedIn, router, pathname]);

  if (!ready) {
    return (
      <div className="ops-auth-loading" role="status">
        Checking staff session…
      </div>
    );
  }
  if (!signedIn) return null;
  return children;
}

export function OpsAuthBanner() {
  const context = useContext(OpsAuthContext);
  if (!context) return null;
  const { session, signOut } = context;
  const email = session?.email;
  if (!email && !opsBearerToken()) return null;
  return (
    <div className="ops-auth signed-in">
      <span>{email ?? 'Staff session active'}</span>
      <Button
        type="button"
        onClick={() => {
          signOut();
          window.location.assign('/login');
        }}
      >
        Sign out
      </Button>
    </div>
  );
}
