'use client';

import {
  type ReactNode,
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
} from 'react';
import { usePathname, useRouter } from 'next/navigation';
import { OPS_AUTH_TOKEN } from './config';
import { Button } from './ui/button';

const SESSION_KEY = 'serv60.ops.session';
const TOKEN_KEY = 'serv60.ops.token';

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
  if (typeof window === 'undefined') return null;
  try {
    const raw = window.localStorage.getItem(SESSION_KEY);
    if (!raw) return null;
    const parsed = JSON.parse(raw) as OpsSession;
    if (!parsed?.email) return null;
    return parsed;
  } catch {
    return null;
  }
}

export function isOpsSignedIn(): boolean {
  return readOpsSession() != null || Boolean(opsBearerToken());
}

export function signInOpsSession(session: Omit<OpsSession, 'signedInAt'>): void {
  const value: OpsSession = { ...session, signedInAt: new Date().toISOString() };
  window.localStorage.setItem(SESSION_KEY, JSON.stringify(value));
  // Same-tab storage events do not fire — notify the in-memory provider.
  window.dispatchEvent(new Event('serv60-ops-auth'));
}

function opsBearerToken(): string {
  if (typeof window === 'undefined') return OPS_AUTH_TOKEN;
  return window.localStorage.getItem(TOKEN_KEY) ?? OPS_AUTH_TOKEN;
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
    window.localStorage.removeItem(SESSION_KEY);
    window.localStorage.removeItem(TOKEN_KEY);
    setSession(null);
    window.dispatchEvent(new Event('serv60-ops-auth'));
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
  const signedIn =
    session != null || readOpsSession() != null || Boolean(opsBearerToken());

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
