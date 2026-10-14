import { Suspense } from 'react';
import { LoginExperience } from '../../login-experience';

export default function LoginPage() {
  return (
    <Suspense
      fallback={
        <main className="login-page">
          <div className="ops-auth-loading" role="status">
            Preparing staff sign-in…
          </div>
        </main>
      }
    >
      <LoginExperience />
    </Suspense>
  );
}
