import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { BookingCard, type CalendarEntry } from '../app/booking-card';
import { HubProvider } from '../app/hub-context';

const job: CalendarEntry = { allocationId: 'allocation-1', holdId: 'hold-1', resourceCode: 'EXPRESS-01', serviceCode: 'EXPRESS_OIL', startsAt: '2026-10-12T10:00:00Z', endsAt: '2026-10-12T11:00:00Z', status: 'CONFIRMED' };
const details = { holdId: job.holdId, contact: { customerName: 'Alex Demo', phoneNumber: '+27 00 000 0000', vehicleMake: 'Toyota', vehicleModel: 'Corolla', registration: 'DEMO 060' }, complimentaryServiceCode: 'PRIVATE_WORK' };
function mount() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  render(<QueryClientProvider client={client}><HubProvider><BookingCard job={job} /></HubProvider></QueryClientProvider>);
}
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });

describe('booking card', () => {
  it('loads details only on click and returns keyboard focus when flipped back', async () => {
    const fetcher = vi.fn().mockResolvedValue({ ok: true, json: async () => details });
    vi.stubGlobal('fetch', fetcher);
    mount();
    expect(fetcher).not.toHaveBeenCalled();
    const user = userEvent.setup();
    const summary = screen.getByRole('button', { name: /View EXPRESS OIL/ });
    summary.focus();
    await user.keyboard('{Enter}');
    expect(await screen.findByText('Alex Demo')).toBeTruthy();
    expect(screen.getByText('Toyota Corolla')).toBeTruthy();
    expect(screen.getByText('DEMO 060')).toBeTruthy();
    expect(screen.getByText('Private workspace')).toBeTruthy();
    expect(screen.getByRole('link', { name: '+27 00 000 0000' }).getAttribute('href')).toBe('tel:+27000000000');
    expect(fetcher).toHaveBeenCalledWith(expect.stringContaining('/appointments/hold-1/details'), expect.objectContaining({ cache: 'no-store', signal: expect.any(AbortSignal) }));
    await user.click(screen.getByRole('button', { name: /Back/ }));
    expect(screen.queryByText('Alex Demo')).toBeNull();
    await waitFor(() => expect(document.activeElement).toBe(summary));
  });
  it('shows loading and a recoverable error without inventing customer data', async () => {
    let resolve!: (response: unknown) => void;
    const fetcher = vi.fn().mockImplementationOnce(() => new Promise((done) => { resolve = done; }))
      .mockResolvedValueOnce({ ok: true, json: async () => details });
    vi.stubGlobal('fetch', fetcher);
    mount();
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: /View EXPRESS OIL/ }));
    expect(screen.getByRole('status').textContent).toContain('Loading');
    resolve({ ok: false, status: 503 });
    expect(await screen.findByRole('alert')).toBeTruthy();
    await user.click(screen.getByRole('button', { name: 'Try again' }));
    expect(await screen.findByText('Alex Demo')).toBeTruthy();
  });
  it('shows missing information explicitly on older appointments', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => ({ ...details, contact: null, complimentaryServiceCode: null }) }));
    mount();
    await userEvent.click(screen.getByRole('button', { name: /View EXPRESS OIL/ }));
    expect(await screen.findAllByText('Not provided')).toHaveLength(4);
    expect(screen.queryByRole('link')).toBeNull();
  });
  it('explains when the operator is not authorized', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: false, status: 403 }));
    mount();
    await userEvent.click(screen.getByRole('button', { name: /View EXPRESS OIL/ }));
    expect(await screen.findByText('Only hub staff can view these details.')).toBeTruthy();
    expect(screen.queryByText('Alex Demo')).toBeNull();
  });
});
