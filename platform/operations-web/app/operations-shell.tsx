'use client';

import Link from 'next/link';
import { usePathname } from 'next/navigation';
import { type MouseEvent, type ReactNode, useEffect, useState } from 'react';
import { OpsAuthBanner } from './ops-auth';

const LINKS = [
  { href: '/', label: 'Overview', mark: '01' },
  { href: '/#insights', label: 'Insights', mark: '02' },
  { href: '/#floor', label: 'Live floor', mark: '03' },
  { href: '/#calendar', label: 'Service floor', mark: '04' },
  { href: '/#dispatch', label: 'Roadside dispatch', mark: '05' },
  { href: '/#parts', label: 'Parts readiness', mark: '06' },
  { href: '/settings', label: 'Hub settings', mark: '07' },
] as const;

function hashFromHref(href: string): string | null {
  const index = href.indexOf('#');
  if (index < 0) return null;
  return href.slice(index + 1) || null;
}

function scrollToHash(hash: string) {
  document.getElementById(hash)?.scrollIntoView({ behavior: 'smooth', block: 'start' });
}

export function OperationsShell({ children }: { children: ReactNode }) {
  const path = usePathname();
  const [hash, setHash] = useState('');

  // biome-ignore lint/correctness/useExhaustiveDependencies: re-read the hash after every route change
  useEffect(() => {
    const read = () => setHash(window.location.hash.replace(/^#/, ''));
    read();
    window.addEventListener('hashchange', read);
    return () => window.removeEventListener('hashchange', read);
  }, [path]);

  useEffect(() => {
    if (path !== '/' || !hash) return;
    const timer = window.setTimeout(() => scrollToHash(hash), 50);
    return () => window.clearTimeout(timer);
  }, [path, hash]);

  function onHashNav(event: MouseEvent<HTMLAnchorElement>, href: string) {
    const next = hashFromHref(href);
    if (!next) return;
    if (path !== '/') return; // Next navigates to /; effect scrolls
    event.preventDefault();
    scrollToHash(next);
    window.history.replaceState(null, '', href);
    setHash(next);
  }

  function onOverviewNav(event: MouseEvent<HTMLAnchorElement>) {
    if (path !== '/') return;
    event.preventDefault();
    window.scrollTo({ top: 0, behavior: 'smooth' });
    window.history.replaceState(null, '', '/');
    setHash('');
  }

  return (
    <div className="operations-layout">
      <aside className="operations-sidebar">
        <Link href="/" className="wordmark" aria-label="Serv60 overview">
          serv<span>60</span>
          <i aria-hidden="true" />
        </Link>
        <div className="workspace-label">
          <span className="status-dot" /> HUB OPERATIONS
        </div>
        <nav aria-label="Operations navigation">
          {LINKS.map((link) => {
            const linkHash = hashFromHref(link.href);
            const current =
              link.href === '/settings'
                ? path === '/settings'
                : link.href === '/'
                  ? path === '/' && !hash
                  : path === '/' && hash === linkHash;

            return (
              <Link
                key={link.href}
                href={link.href}
                aria-current={current ? 'page' : undefined}
                onClick={
                  linkHash
                    ? (event) => onHashNav(event, link.href)
                    : link.href === '/'
                      ? onOverviewNav
                      : undefined
                }
              >
                <span aria-hidden="true">{link.mark}</span>
                {link.label}
              </Link>
            );
          })}
        </nav>
        <OpsAuthBanner />
        <div className="sidebar-note">
          <span className="eyebrow">YOUR HUB. YOUR RHYTHM.</span>
          <p>
            Keep the floor moving.
            <br />
            Give every hour purpose.
          </p>
        </div>
      </aside>
      <div className="operations-content">{children}</div>
    </div>
  );
}
