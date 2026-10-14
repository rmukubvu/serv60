'use client';

import { useSearchParams } from 'next/navigation';
import { type FormEvent, useEffect, useState } from 'react';
import { DEMO_MODE } from './config';
import {
  AUTH0_LOGIN_URL,
  isAuth0Configured,
  isOpsSignedIn,
  signInOpsSession,
  signInWithToken,
} from './ops-auth';
import { Button } from './ui/button';

const FEATURES = [
  'Live service-floor calendar across bays',
  'Roadside dispatch map and partner queue',
  'Parts readiness before every hour starts',
  'Hub hours, capacity, and complimentary options',
  'Staff sessions scoped to your hub membership',
];

function safeNext(raw: string | null): string {
  if (!raw || !raw.startsWith('/') || raw.startsWith('//')) return '/';
  return raw;
}

export function LoginExperience() {
  const params = useSearchParams();
  const [email, setEmail] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const auth0 = isAuth0Configured();
  const next = safeNext(params.get('next'));

  useEffect(() => {
    if (isOpsSignedIn()) {
      window.location.replace(next);
    }
  }, [next]);

  async function onSubmit(event: FormEvent) {
    event.preventDefault();
    setError('');
    const trimmed = email.trim();
    if (DEMO_MODE && !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(trimmed)) {
      setError('Enter a valid staff email to continue.');
      return;
    }
    setBusy(true);
    try {
      // Outside demo mode the field holds a staff access token, verified by the API before use.
      if (DEMO_MODE) signInOpsSession({ email: trimmed, method: 'staff-email' });
      else await signInWithToken(trimmed);
      // Full navigation so OpsAuthProvider reloads session cleanly.
      window.location.assign(next);
    } catch (caught) {
      setError(caught instanceof Error ? caught.message : 'Sign-in failed.');
      setBusy(false);
    }
  }

  return (
    <main className="login-page">
      <div className="login-atmosphere" aria-hidden="true" />
      <div className="login-shell">
        <section className="login-brand" aria-labelledby="login-brand-title">
          <a href="/login" className="wordmark" aria-label="Serv60">
            serv<span>60</span>
            <i aria-hidden="true" />
          </a>
          <p className="eyebrow">HUB OPERATIONS</p>
          <h1 id="login-brand-title">
            Keep the floor moving.
            <br />
            Give every hour purpose.
          </h1>
          <p className="login-lead">
            Sign in to run calendars, roadside dispatch, and hub settings for your Serv60
            location — the same calm brand language your customers already trust.
          </p>
          <ul className="login-features" role="list">
            {FEATURES.map((feature) => (
              <li key={feature}>
                <svg viewBox="0 0 24 24" width="18" height="18" aria-hidden="true">
                  <path
                    fill="currentColor"
                    d="m10 15.586-3.293-3.293-1.414 1.414L10 18.414l9.707-9.707-1.414-1.414z"
                  />
                </svg>
                {feature}
              </li>
            ))}
          </ul>
        </section>

        <section className="login-panel" aria-labelledby="login-panel-title">
          <div className="login-panel-frame" aria-hidden="true">
            <svg viewBox="0 0 1013 890" fill="none" xmlns="http://www.w3.org/2000/svg">
              <path
                d="M180.462 795.681L40.8315 567.776C19.5801 533.09 8.95466 515.747 4.64446 497.091C0.831363 480.586 0.608543 463.413 3.99145 446.712C7.81567 427.835 17.9843 409.924 38.3214 374.102L184.235 117.101C203.682 82.8483 213.405 65.722 227.028 52.9648C239.084 41.6743 253.303 32.9153 268.81 27.225C286.332 20.7958 306.003 19.8141 345.345 17.8502L644.507 2.91668C685.874 0.851143 706.557 -0.18069 725.22 4.94606C741.731 9.48141 757.023 17.479 770.074 28.4042C784.827 40.7544 795.51 58.1913 816.877 93.066L955.838 319.878C977.009 354.434 987.595 371.712 991.91 390.3C995.728 406.747 995.98 423.86 992.649 440.515C988.883 459.337 978.815 477.215 958.678 512.969L811.011 775.156C791.315 810.127 781.466 827.612 767.593 840.563C755.316 852.023 740.811 860.86 724.997 866.515C707.127 872.905 687.074 873.638 646.968 875.103L350.229 885.943C309.449 887.433 289.06 888.178 270.689 882.944C254.434 878.313 239.396 870.318 226.557 859.48C212.045 847.23 201.517 830.047 180.462 795.681Z"
                fill="#4db8ff"
              />
            </svg>
          </div>

          <div className="login-card">
            <p className="eyebrow">STAFF ACCESS</p>
            <h2 id="login-panel-title">Welcome back</h2>
            <p className="login-card-copy">
              {DEMO_MODE
                ? 'Local demo: enter any staff email. The API is running without sign-in.'
                : 'Paste your staff access token. It is checked against this hub before you enter.'}
            </p>

            {auth0 ? (
              <a className="primary login-auth0" href={AUTH0_LOGIN_URL}>
                Continue with Auth0
              </a>
            ) : null}

            {auth0 ? <div className="login-divider">or continue with email</div> : null}

            <form className="login-form" onSubmit={onSubmit}>
              <label htmlFor="ops-email">{DEMO_MODE ? 'Work email' : 'Staff access token'}</label>
              <input
                id="ops-email"
                className="ui-input"
                type={DEMO_MODE ? 'email' : 'password'}
                autoComplete={DEMO_MODE ? 'username' : 'off'}
                inputMode={DEMO_MODE ? 'email' : 'text'}
                placeholder={DEMO_MODE ? 'you@yourhub.co.za' : 'eyJhbGciOi…'}
                value={email}
                onChange={(event) => setEmail(event.target.value)}
                disabled={busy}
                required
              />
              {error ? <p className="form-error">{error}</p> : null}
              <Button type="submit" className="primary" disabled={busy}>
                {busy ? 'Signing in…' : 'Enter operations'}
              </Button>
            </form>

            <p className="login-footnote">
              Secured staff entry for Serv60 hubs. Customers sign in on the mobile app with
              Clerk — this workspace is for operators only.
            </p>
          </div>
        </section>
      </div>
    </main>
  );
}
