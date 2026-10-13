import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, expect, it, vi } from 'vitest';
import { HubSettingsView } from '../app/hub-settings-view';
import { type HubSettings, settingsRequest } from '../app/hub-settings-api';
import { HubOverview } from '../app/hub-overview';
import { CalendarView } from '../app/calendar-view';
import { OperationsShell } from '../app/operations-shell';
import SettingsPage from '../app/settings/page';

vi.mock('next/navigation', () => ({ usePathname: () => '/settings' }));
vi.mock('next/link', () => ({ default: ({ children, ...props }: React.AnchorHTMLAttributes<HTMLAnchorElement>) => <a {...props}>{children}</a> }));
afterEach(() => { cleanup(); vi.unstubAllGlobals(); });
const fixture: HubSettings = { hubId: 'hub', name: 'City Bowl', timezone: 'Africa/Johannesburg', published: true, revision: 2,
  capacity: { expressBays: 4, barberChairs: 1, massageRooms: 0, workspaces: 1 },
  tradingHours: Array.from({ length: 7 }, (_, index) => ({ dayOfWeek: index + 1, closed: index === 6, opensAt: '08:00:00', closesAt: '17:00:00' })),
  resources: [{ id: 'r1', code: 'EXPRESS-01', kind: 'EXPRESS_BAY' }, { id: 'r2', code: 'MASSAGE-01', kind: 'MASSAGE_ROOM' }],
};
const response = (body: unknown, status = 200) => ({ ok: status < 400, status, json: async () => structuredClone(body) });
function mount(element = <HubSettingsView />) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  render(<QueryClientProvider client={client}>{element}</QueryClientProvider>);
  return client;
}

it('loads real capacity and enables edits through the settings navigation', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response(fixture)));
  mount(<OperationsShell><SettingsPage /></OperationsShell>);
  expect(screen.getByRole('status').textContent).toContain('Loading');
  expect(await screen.findByLabelText('Express service bays')).toHaveProperty('value', '4');
  expect(screen.getByRole('link', { name: /Hub settings/ }).getAttribute('aria-current')).toBe('page');
  expect(screen.getByRole('button', { name: 'Save changes' })).toHaveProperty('disabled', true);
  expect(screen.getByLabelText('Sunday opening time')).toHaveProperty('disabled', true);
  expect(screen.getByLabelText('Sunday open')).toHaveProperty('checked', false);
  expect(screen.getByText('54')).toBeTruthy();
});

it('saves bays, facilities and hours atomically using the loaded revision', async () => {
  const fetcher = vi.fn().mockResolvedValueOnce(response(fixture)).mockImplementationOnce(async (_url, init) => response({ ...fixture, ...JSON.parse(init.body), revision: 3 }));
  vi.stubGlobal('fetch', fetcher); mount(); const user = userEvent.setup();
  fireEvent.change(await screen.findByLabelText('Express service bays'), { target: { value: '6' } });
  fireEvent.change(screen.getByLabelText('Barber'), { target: { value: '2' } });
  fireEvent.change(screen.getByLabelText('Massage'), { target: { value: '1' } });
  fireEvent.change(screen.getByLabelText('Private workspace'), { target: { value: '3' } });
  await user.click(screen.getByLabelText('Sunday open'));
  fireEvent.change(screen.getByLabelText('Sunday opening time'), { target: { value: '09:30' } });
  fireEvent.change(screen.getByLabelText('Sunday closing time'), { target: { value: '13:30' } });
  const event = new Event('beforeunload', { cancelable: true }); window.dispatchEvent(event); expect(event.defaultPrevented).toBe(true);
  await user.click(screen.getByRole('button', { name: 'Save changes' }));
  await waitFor(() => expect(screen.getByRole('status').textContent).toContain('Settings saved'));
  const request = JSON.parse(fetcher.mock.calls[1][1].body);
  expect(request).toMatchObject({ revision: 2, capacity: { expressBays: 6, barberChairs: 2, massageRooms: 1, workspaces: 3 } });
  expect(request.tradingHours[6]).toEqual({ dayOfWeek: 7, closed: false, opensAt: '09:30:00', closesAt: '13:30:00' });
  expect(fetcher.mock.calls[1][1].method).toBe('PUT');
  expect(screen.getByRole('button', { name: 'Save changes' })).toHaveProperty('disabled', true);
});

it('requires at least an hour for each open day', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response(fixture))); mount();
  await screen.findByLabelText('Monday closing time');
  fireEvent.change(screen.getByLabelText('Monday closing time'), { target: { value: '08:30' } });
  expect(screen.getByRole('alert').textContent).toContain('one full hour');
  expect(screen.getByRole('button', { name: 'Save changes' })).toHaveProperty('disabled', true);
  fireEvent.change(screen.getByLabelText('Monday closing time'), { target: { value: '09:00' } });
  expect(screen.queryByRole('alert')).toBeNull();
  expect(screen.getByRole('button', { name: 'Save changes' })).toHaveProperty('disabled', false);
});

it('retains unsaved edits after a conflict and a failed reload', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(response(fixture)).mockResolvedValueOnce(response({ error: 'Resources have upcoming bookings' }, 409)).mockRejectedValueOnce(new Error('offline')).mockResolvedValueOnce(response(fixture)));
  mount(); fireEvent.change(await screen.findByLabelText('Express service bays'), { target: { value: '2' } });
  const user = userEvent.setup(); await user.click(screen.getByRole('button', { name: 'Save changes' }));
  expect((await screen.findByRole('alert')).textContent).toContain('upcoming bookings');
  expect(screen.getByLabelText('Express service bays')).toHaveProperty('value', '2');
  await user.click(screen.getByRole('button', { name: 'Reload saved settings' }));
  expect(await screen.findByText('Could not reload. Your unsaved changes are still here.')).toBeTruthy();
  expect(screen.getByLabelText('Express service bays')).toHaveProperty('value', '2');
  await user.click(screen.getByRole('button', { name: 'Reload saved settings' }));
  await waitFor(() => expect(screen.getByLabelText('Express service bays')).toHaveProperty('value', '4'));
});

it('retries failed reads and handles unpublished hubs', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(response({}, 503)).mockResolvedValueOnce(response({ ...fixture, published: false })));
  mount(); expect((await screen.findByRole('alert')).textContent).toContain('503');
  await userEvent.setup().click(screen.getByRole('button', { name: 'Try again' }));
  expect(await screen.findByText(/Not yet published/)).toBeTruthy();
});

it('returns a useful generic conflict without a server explanation', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response({}, 409)));
  await expect(settingsRequest({ revision: 0, capacity: fixture.capacity, tradingHours: fixture.tradingHours })).rejects.toThrow('Settings conflict');
});

it('prevents duplicate submits during saving and reports server errors', async () => {
  let resolve: (value: unknown) => void = () => {};
  vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(response(fixture)).mockImplementationOnce(() => new Promise(r => { resolve = r; })));
  mount(); fireEvent.change(await screen.findByLabelText('Express service bays'), { target: { value: '7' } });
  await userEvent.setup().click(screen.getByRole('button', { name: 'Save changes' }));
  expect(screen.getByRole('button', { name: 'Saving…' })).toHaveProperty('disabled', true);
  resolve(response({}, 500)); expect((await screen.findByRole('alert')).textContent).toContain('Unable to save');
});

it('shows idle resources and classifies massage as lifestyle', async () => {
  vi.stubGlobal('fetch', vi.fn().mockImplementation(async url => response(String(url).endsWith('/settings') ? fixture : [])));
  mount(<CalendarView />); expect(await screen.findByText('EXPRESS-01')).toBeTruthy();
  expect(screen.getAllByText('No bookings today')).toHaveLength(2);
  await userEvent.setup().click(screen.getByRole('button', { name: 'Lifestyle' }));
  expect(screen.queryByText('EXPRESS-01')).toBeNull(); expect(screen.getByText('MASSAGE-01')).toBeTruthy();
  await userEvent.setup().click(screen.getByRole('button', { name: 'Automotive' }));
  expect(screen.queryByText('MASSAGE-01')).toBeNull();
});

it('keeps empty filters usable and reports unavailable calendar data', async () => {
  vi.stubGlobal('fetch', vi.fn().mockImplementation(async url => response(String(url).endsWith('/settings') ? { ...fixture, resources: [] } : [])));
  mount(<CalendarView />); expect(await screen.findByText('No resources match this filter.')).toBeTruthy(); cleanup();
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response({}, 503))); mount(<CalendarView />);
  expect((await screen.findByRole('alert')).textContent).toContain('Unable to load');
});

it('shows live counts without double-counting a visit using two resources', async () => {
  vi.stubGlobal('fetch', vi.fn().mockImplementation(async url => response(String(url).endsWith('/settings') ? fixture : [{ holdId: 'same' }, { holdId: 'same' }])));
  mount(<HubOverview />); const summary = await screen.findByRole('region', { name: 'Hub summary' });
  await waitFor(() => expect(within(summary).getByText('Scheduled today').parentElement?.textContent).toContain('1'));
  expect(within(summary).getByText('Express bays').parentElement?.textContent).toContain('4'); cleanup();
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response({}, 503))); mount(<HubOverview />);
  expect((await screen.findByRole('alert')).textContent).toContain('configuration is unavailable');
});

it('transitions real held bookings and leaves the calendar unchanged on a rejected action', async () => {
  const job = { allocationId: 'allocation', holdId: 'hold', resourceCode: 'EXPRESS-01', serviceCode: 'EXPRESS_OIL', startsAt: '2035-01-03T08:00:00Z', endsAt: '2035-01-03T09:00:00Z', status: 'HELD' };
  let writes = 0;
  const fetcher = vi.fn().mockImplementation(async (url, init) => {
    if (init?.method === 'POST') { writes++; return writes === 1 ? response({ error: 'Hold expired' }, 409) : response({ status: 'CANCELLED' }); }
    return response(String(url).endsWith('/settings') ? fixture : [job]);
  });
  vi.stubGlobal('fetch', fetcher); mount(<CalendarView />);
  await userEvent.setup().click(await screen.findByRole('button', { name: 'Confirm' }));
  expect((await screen.findByRole('alert')).textContent).toContain('Hold expired');
  await userEvent.setup().click(screen.getByRole('button', { name: 'Cancel' }));
  await waitFor(() => expect(screen.queryByRole('alert')).toBeNull());
  expect(writes).toBe(2);
  expect(fetcher.mock.calls.some(([url]) => String(url).endsWith('/hold/cancel'))).toBe(true);
});
