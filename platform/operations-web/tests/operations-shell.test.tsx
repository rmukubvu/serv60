import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, expect, it, vi } from 'vitest';
import { OperationsShell } from '../app/operations-shell';

const pathname = vi.fn(() => '/');

vi.mock('next/navigation', () => ({
  usePathname: () => pathname(),
  useRouter: () => ({ replace: vi.fn(), push: vi.fn() }),
}));

vi.mock('next/link', () => ({
  default: ({
    children,
    href,
    onClick,
    ...props
  }: React.AnchorHTMLAttributes<HTMLAnchorElement>) => (
    <a href={href} onClick={onClick} {...props}>
      {children}
    </a>
  ),
}));

vi.mock('../app/ops-auth', () => ({
  OpsAuthBanner: () => null,
}));

afterEach(() => {
  cleanup();
  pathname.mockReturnValue('/');
  window.history.replaceState(null, '', '/');
});

it('scrolls to hash targets from the sidebar when already on overview', () => {
  const calendar = document.createElement('section');
  calendar.id = 'calendar';
  calendar.scrollIntoView = vi.fn();
  document.body.append(calendar);

  render(
    <OperationsShell>
      <div>content</div>
    </OperationsShell>,
  );

  fireEvent.click(screen.getByRole('link', { name: /Service floor/ }));
  expect(calendar.scrollIntoView).toHaveBeenCalled();
  expect(window.location.hash).toBe('#calendar');
  expect(screen.getByRole('link', { name: /Service floor/ }).getAttribute('aria-current')).toBe(
    'page',
  );

  fireEvent.click(screen.getByRole('link', { name: /^Overview$/ }));
  expect(window.location.hash).toBe('');
  calendar.remove();
});

it('marks hub settings as current on the settings route', () => {
  pathname.mockReturnValue('/settings');
  render(
    <OperationsShell>
      <div>settings</div>
    </OperationsShell>,
  );
  expect(screen.getByRole('link', { name: /Hub settings/ }).getAttribute('aria-current')).toBe(
    'page',
  );
});
