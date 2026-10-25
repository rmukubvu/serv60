import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, describe, expect, it, vi } from 'vitest';
import { apiFetch } from '../app/api';
import { HUB_ID } from '../app/config';
import { HubProvider } from '../app/hub-context';
import {
  complimentaryLabel,
  elapsedLabel,
  type FloorVisit,
  floorMinutes,
  groupFloor,
  isOverTarget,
  LiveFloor,
  nextStageLabel,
  rand,
  randsToMinor,
  sastLabel,
  stageLabel,
  VISIT_STAGES,
} from '../app/live-floor';

vi.mock('../app/api', () => ({ apiFetch: vi.fn() }));
const fetcher = vi.mocked(apiFetch);

const response = (body: unknown, status = 200) =>
  ({ ok: status < 400, status, json: async () => structuredClone(body) }) as unknown as Response;

const minutesAgo = (minutes: number) => new Date(Date.now() - minutes * 60_000).toISOString();

const base: FloorVisit = {
  visitId: 'visit-base',
  startsAt: '2026-10-24T08:00:00+02:00',
  endsAt: '2026-10-24T09:00:00+02:00',
  bookingStatus: 'CONFIRMED',
  stage: null,
  checkedInAt: null,
  readyAt: null,
  handedOverAt: null,
  elapsedMinutes: null,
  targetMinutes: 60,
  customerName: 'Base Customer',
  vehicle: 'Car',
  registration: null,
  complimentaryServiceCode: null,
  bayCode: null,
  pendingScopeChanges: 0,
  approvedExtrasMinor: 0,
  invoiceNumber: null,
  invoiceTotalMinor: null,
  nextStages: ['CHECKED_IN'],
};

const arriving: FloorVisit = {
  ...base,
  visitId: 'visit-arriving',
  customerName: 'Sam Early',
  vehicle: 'VW Polo',
  registration: 'CA 123-456',
  complimentaryServiceCode: 'BARBER',
};

const servicing: FloorVisit = {
  ...base,
  visitId: 'visit-servicing',
  stage: 'SERVICING',
  checkedInAt: minutesAgo(23.2),
  elapsedMinutes: 23,
  customerName: 'Alex Demo',
  vehicle: 'Toyota Corolla',
  registration: 'DEMO 060',
  complimentaryServiceCode: 'PRIVATE_WORK',
  bayCode: 'EXPRESS-01',
  pendingScopeChanges: 1,
  approvedExtrasMinor: 145000,
  nextStages: ['QUALITY_CHECK'],
};

const late: FloorVisit = {
  ...base,
  visitId: 'visit-late',
  stage: 'WASHING',
  checkedInAt: minutesAgo(71.2),
  elapsedMinutes: 71,
  customerName: 'Jo Late',
  vehicle: 'Ford Ranger',
  bayCode: 'EXPRESS-02',
  nextStages: ['READY'],
};

const ready: FloorVisit = {
  ...base,
  visitId: 'visit-ready',
  stage: 'READY',
  checkedInAt: minutesAgo(90),
  readyAt: minutesAgo(42),
  elapsedMinutes: 48,
  customerName: 'Kim Ready',
  vehicle: 'BMW 3 Series',
  complimentaryServiceCode: 'MASSAGE',
  nextStages: ['HANDED_OVER'],
};

const done: FloorVisit = {
  ...base,
  visitId: 'visit-done',
  stage: 'HANDED_OVER',
  checkedInAt: minutesAgo(200),
  readyAt: minutesAgo(150),
  handedOverAt: minutesAgo(140),
  elapsedMinutes: 50,
  customerName: 'Pat Gone',
  vehicle: 'Hyundai i20',
  nextStages: [],
};

type Post = { path: string; body: unknown };
/** One connection to the floor stream; the test plays the server. */
type Stream = {
  send: (event: string, data: unknown) => void;
  push: (text: string) => void;
  close: () => void;
  cancelled: boolean;
};
type Board = { posts: Post[]; rows: FloorVisit[]; streams: Stream[]; load: () => Response };

const encoder = new TextEncoder();

/** A text/event-stream Response that stays open until the test closes it or the board hangs up. */
function streamResponse(board: Board): Response {
  let controller!: ReadableStreamDefaultController<Uint8Array>;
  const stream: Stream = {
    push: (text) => controller.enqueue(encoder.encode(text)),
    send: (event, data) => stream.push(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`),
    close: () => controller.close(),
    cancelled: false,
  };
  const body = new ReadableStream<Uint8Array>({
    start(c) {
      controller = c;
    },
    cancel() {
      stream.cancelled = true;
    },
  });
  board.streams.push(stream);
  return new Response(body, { status: 200, headers: { 'Content-Type': 'text/event-stream' } });
}

/**
 * Serves the board from `rows` and opens a floor stream the test can push events on; a test answers POSTs
 * and may swap the rows the next poll returns.
 */
function serve(
  rows: FloorVisit[],
  reply: (post: Post, board: Board) => Response = () => response({}, 201),
): Board {
  const board: Board = { posts: [], rows, streams: [], load: () => response(board.rows) };
  fetcher.mockImplementation(async (path, init) => {
    if (path.endsWith('/floor/stream')) return streamResponse(board);
    if (init?.method === 'POST') {
      const post = { path, body: init.body ? JSON.parse(String(init.body)) : undefined };
      board.posts.push(post);
      return reply(post, board);
    }
    return board.load();
  });
  return board;
}

/** How many times the board itself was fetched (not the stream, not a POST). */
const boardLoads = () =>
  fetcher.mock.calls.filter(([path, init]) => init?.method !== 'POST' && !path.endsWith('/floor/stream')).length;

function mount() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  render(
    <QueryClientProvider client={client}>
      <HubProvider>
        <LiveFloor />
      </HubProvider>
    </QueryClientProvider>,
  );
  return client;
}

const cardOf = (customer: string) => screen.getByText(customer).closest('article') as HTMLElement;

afterEach(() => {
  cleanup();
  vi.useRealTimers();
});

describe('live floor helpers', () => {
  it('groups cars by where they are, keeping server order within each group', () => {
    const groups = groupFloor([arriving, servicing, done, late, ready]);
    expect(groups.onFloor.map((row) => row.visitId)).toEqual(['visit-servicing', 'visit-late', 'visit-ready']);
    expect(groups.arriving.map((row) => row.visitId)).toEqual(['visit-arriving']);
    expect(groups.done.map((row) => row.visitId)).toEqual(['visit-done']);
    expect(groupFloor([])).toEqual({ onFloor: [], arriving: [], done: [] });
  });

  it('labels every stage and the action that records it', () => {
    expect(VISIT_STAGES.map(stageLabel)).toEqual([
      'Checked in',
      'Inspecting',
      'Servicing',
      'Quality check',
      'Washing',
      'Ready for collection',
      'Handed over',
    ]);
    expect(VISIT_STAGES.map(nextStageLabel)).toEqual([
      'Check in',
      'Start inspection',
      'Start service',
      'Quality check',
      'To wash',
      'Ready for collection',
      'Hand over',
    ]);
    expect(complimentaryLabel('BARBER')).toBe('Barber');
    expect(complimentaryLabel('PRIVATE_WORK')).toBe('Private workspace');
    expect(complimentaryLabel('MASSAGE')).toBe('Massage');
    expect(complimentaryLabel(null)).toBeNull();
  });

  it('counts minutes from check-in and freezes the clock once the car is ready', () => {
    const now = Date.parse('2026-10-24T09:00:00+02:00');
    const row = { ...base, checkedInAt: '2026-10-24T08:36:30+02:00' };
    expect(floorMinutes(row, now)).toBe(23);
    expect(elapsedLabel(row, now)).toBe('23 of 60 min');
    expect(elapsedLabel({ ...row, readyAt: '2026-10-24T08:50:00+02:00' }, now)).toBe('13 of 60 min');
    expect(floorMinutes({ ...row, handedOverAt: '2026-10-24T08:40:00+02:00' }, now)).toBe(3);
    expect(elapsedLabel(base, now)).toBe('Not checked in');
    expect(floorMinutes({ ...row, checkedInAt: 'not a date', elapsedMinutes: 7 }, now)).toBe(7);
    expect(floorMinutes({ ...row, checkedInAt: '2026-10-24T09:30:00+02:00' }, now)).toBe(0);
    expect(floorMinutes({ ...row, readyAt: 'not a date' }, now)).toBe(23);
    expect(isOverTarget(row, now)).toBe(false);
    expect(isOverTarget({ ...row, checkedInAt: '2026-10-24T07:59:00+02:00' }, now)).toBe(true);
    expect(isOverTarget(base, now)).toBe(false);
  });

  it('formats rands and turns staff input into cents', () => {
    expect(rand(145000)).toBe('R1,450.00');
    expect(rand(0)).toBe('R0.00');
    expect(rand(5)).toBe('R0.05');
    expect(rand(-250)).toBe('-R2.50');
    expect(randsToMinor('1450.00')).toBe(145000);
    expect(randsToMinor('99.99')).toBe(9999);
    expect(randsToMinor('1 450,50')).toBe(145050);
    expect(randsToMinor('12.5')).toBe(1250);
    expect(randsToMinor('brakes')).toBeNaN();
  });

  it('shows hub wall-clock times in SAST', () => {
    expect(sastLabel('2026-10-24T06:05:00Z')).toBe('08:05');
    expect(sastLabel('2026-10-24T08:00:00+02:00')).toBe('08:00');
    expect(sastLabel(null)).toBeNull();
    expect(sastLabel('later')).toBeNull();
  });
});

describe('LiveFloor', () => {
  it('shows arriving, on-floor and handed-over cars with stage, clock and extras', async () => {
    serve([arriving, servicing, late, ready, done]);
    mount();
    expect(screen.getByRole('status').textContent).toContain('Loading');
    expect(await screen.findByText('Alex Demo')).toBeTruthy();
    expect(fetcher).toHaveBeenCalledWith(`/v1/hubs/${HUB_ID}/floor`);

    expect(screen.getByRole('heading', { name: /On the floor/ }).textContent).toContain('3');
    expect(screen.getByRole('heading', { name: /Arriving/ }).textContent).toContain('1');
    expect(screen.getByRole('heading', { name: /Handed over/ }).textContent).toContain('1');
    expect(screen.getByText('3 on the floor')).toBeTruthy();

    const card = cardOf('Alex Demo');
    expect(card.getAttribute('data-state')).toBe('floor');
    expect(within(card).getByText('Toyota Corolla · DEMO 060')).toBeTruthy();
    expect(within(card).getByText(/Bay EXPRESS-01 · Booked 08:00 SAST · Private workspace/)).toBeTruthy();
    expect(within(card).getByText('23 of 60 min')).toBeTruthy();
    expect(within(card).getByText('1 awaiting customer')).toBeTruthy();
    expect(within(card).getByText(/Approved extras R1,450\.00/)).toBeTruthy();
    expect(within(card).queryByText('Over target')).toBeNull();
    const chips = within(within(card).getByRole('list', { name: 'Stage progress' })).getAllByRole('listitem');
    expect(chips.map((chip) => chip.getAttribute('data-state'))).toEqual([
      'done',
      'done',
      'current',
      'upcoming',
      'upcoming',
      'upcoming',
      'upcoming',
    ]);
    expect(chips[2].textContent).toContain('Servicing');
    expect(chips[2].getAttribute('aria-current')).toBe('step');
    expect(within(card).getByRole('button', { name: 'Quality check' })).toBeTruthy();
    expect(within(card).getByRole('button', { name: 'Propose extra work' })).toBeTruthy();

    const lateCard = cardOf('Jo Late');
    expect(lateCard.getAttribute('data-state')).toBe('over');
    expect(within(lateCard).getByText('71 of 60 min')).toBeTruthy();
    expect(within(lateCard).getByText('Over target')).toBeTruthy();
    expect(within(lateCard).getByText('Ford Ranger · No registration')).toBeTruthy();

    const readyCard = cardOf('Kim Ready');
    expect(readyCard.getAttribute('data-state')).toBe('ready');
    expect(within(readyCard).getByText('48 of 60 min')).toBeTruthy();
    expect(within(readyCard).getByText(/No bay yet · Booked 08:00 SAST · Massage/)).toBeTruthy();
    expect(within(readyCard).getByRole('button', { name: 'Hand over' })).toBeTruthy();
    expect(within(readyCard).queryByRole('button', { name: 'Propose extra work' })).toBeNull();

    const doneCard = cardOf('Pat Gone');
    expect(doneCard.getAttribute('data-state')).toBe('done');
    expect(within(doneCard).getByText('50 of 60 min')).toBeTruthy();
    expect(within(doneCard).getByText(/Handed over \d\d:\d\d/)).toBeTruthy();
    expect(within(doneCard).queryAllByRole('button')).toHaveLength(0);
    expect(within(doneCard).queryByText(/Invoice/)).toBeNull();

    const arrivingCard = cardOf('Sam Early');
    expect(arrivingCard.getAttribute('data-state')).toBe('arriving');
    expect(within(arrivingCard).getByText('Not checked in yet')).toBeTruthy();
    expect(within(arrivingCard).getByText('Not checked in')).toBeTruthy();
    expect(within(arrivingCard).getByText(/Barber/)).toBeTruthy();
    expect(within(arrivingCard).getByRole('button', { name: 'Check in' })).toBeTruthy();
    expect(within(arrivingCard).queryByRole('button', { name: 'Propose extra work' })).toBeNull();
  });

  it('shows the invoice number and total on a handed-over car once it is invoiced', async () => {
    const invoiced: FloorVisit = {
      ...done,
      approvedExtrasMinor: 145000,
      invoiceNumber: 'S60-20261025-C00000',
      invoiceTotalMinor: 169900,
    };
    serve([servicing, invoiced]);
    mount();
    expect(await screen.findByText('Pat Gone')).toBeTruthy();
    const doneCard = cardOf('Pat Gone');
    expect(within(doneCard).getByText('Invoice S60-20261025-C00000 · R1,699.00')).toBeTruthy();
    expect(within(doneCard).queryByText(/Approved extras/)).toBeNull();
    expect(within(cardOf('Alex Demo')).queryByText(/Invoice/)).toBeNull();
  });

  it('checks an arriving car in and shows the backend view of it straight away', async () => {
    const board = serve([arriving], (_post, state) => {
      const checkedIn: FloorVisit = {
        ...arriving,
        stage: 'CHECKED_IN',
        checkedInAt: new Date().toISOString(),
        elapsedMinutes: 0,
        nextStages: ['INSPECTING'],
      };
      state.rows = [checkedIn];
      return response(checkedIn);
    });
    mount();
    await userEvent.setup().click(await screen.findByRole('button', { name: 'Check in' }));
    await waitFor(() => expect(board.posts).toHaveLength(1));
    expect(board.posts[0]).toEqual({ path: `/v1/hubs/${HUB_ID}/visits/visit-arriving/check-in`, body: {} });
    expect(await screen.findByRole('button', { name: 'Start inspection' })).toBeTruthy();
    await waitFor(() => expect(screen.getByRole('heading', { name: /On the floor/ }).textContent).toContain('1'));
    expect(within(cardOf('Sam Early')).getByText('0 of 60 min')).toBeTruthy();
  });

  it('records the next stage for a car on the floor', async () => {
    const board = serve([servicing], (_post, state) => {
      const next: FloorVisit = { ...servicing, stage: 'QUALITY_CHECK', nextStages: ['WASHING'] };
      state.rows = [next];
      return response(next);
    });
    mount();
    await userEvent.setup().click(await screen.findByRole('button', { name: 'Quality check' }));
    await waitFor(() => expect(board.posts).toHaveLength(1));
    expect(board.posts[0]).toEqual({
      path: `/v1/hubs/${HUB_ID}/visits/visit-servicing/stages`,
      body: { stage: 'QUALITY_CHECK' },
    });
    expect(await screen.findByRole('button', { name: 'To wash' })).toBeTruthy();
    await waitFor(() => expect(boardLoads()).toBeGreaterThanOrEqual(2));
  });

  it('proposes extra work in cents and closes the form once the customer has it', async () => {
    const board = serve([servicing], () =>
      response({ id: 'scope-1', visitId: 'visit-servicing', status: 'PROPOSED' }, 201),
    );
    mount();
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Propose extra work' }));
    expect(screen.getByRole('button', { name: 'Propose extra work' }).getAttribute('aria-expanded')).toBe('true');
    expect(screen.getByLabelText('Work description')).toHaveProperty('value', 'Front brake pads');
    expect(screen.getByLabelText('Amount (rands)')).toHaveProperty('value', '1450.00');
    expect(screen.getByLabelText('Note for the customer')).toHaveProperty('value', 'Pads at 2 mm, photos attached');

    await user.click(screen.getByRole('button', { name: 'Send to customer' }));
    await waitFor(() => expect(board.posts).toHaveLength(1));
    expect(board.posts[0]).toEqual({
      path: `/v1/hubs/${HUB_ID}/visits/visit-servicing/scope-changes`,
      body: { items: [{ description: 'Front brake pads', amountMinor: 145000 }], note: 'Pads at 2 mm, photos attached' },
    });
    await waitFor(() => expect(screen.queryByLabelText('Work description')).toBeNull());
  });

  it('sends edited amounts rounded to the cent and lets staff cancel the form', async () => {
    const board = serve([servicing]);
    mount();
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Propose extra work' }));
    fireEvent.change(screen.getByLabelText('Work description'), { target: { value: '  Wiper blades ' } });
    fireEvent.change(screen.getByLabelText('Amount (rands)'), { target: { value: '99.99' } });
    fireEvent.change(screen.getByLabelText('Note for the customer'), { target: { value: '   ' } });
    await user.click(screen.getByRole('button', { name: 'Send to customer' }));
    await waitFor(() => expect(board.posts).toHaveLength(1));
    expect(board.posts[0].body).toEqual({ items: [{ description: 'Wiper blades', amountMinor: 9999 }] });

    await user.click(await screen.findByRole('button', { name: 'Propose extra work' }));
    expect(screen.getByLabelText('Work description')).toHaveProperty('value', 'Front brake pads');
    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByLabelText('Work description')).toBeNull();
    expect(screen.getByRole('button', { name: 'Propose extra work' }).getAttribute('aria-expanded')).toBe('false');
  });

  it('shows the backend reason when a stage is refused and lets staff try again', async () => {
    serve([servicing], () => response({ error: 'SERVICING can only move to QUALITY_CHECK' }, 409));
    mount();
    await userEvent.setup().click(await screen.findByRole('button', { name: 'Quality check' }));
    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toContain('SERVICING can only move to QUALITY_CHECK');
    expect(cardOf('Alex Demo').contains(alert)).toBe(true);
    expect(screen.getByRole('button', { name: 'Quality check' })).toHaveProperty('disabled', false);
  });

  it('shows the backend reason when extra work is refused, and a fallback without one', async () => {
    const board = serve([servicing], (post) =>
      board.posts.length === 1 ? response({ error: 'Visit is already ready for collection' }, 409) : response({}, 500),
    );
    mount();
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Propose extra work' }));
    await user.click(screen.getByRole('button', { name: 'Send to customer' }));
    expect((await screen.findByRole('alert')).textContent).toContain('Visit is already ready for collection');
    expect(screen.getByLabelText('Work description')).toBeTruthy();

    await user.click(screen.getByRole('button', { name: 'Send to customer' }));
    await waitFor(() => expect(screen.getByRole('alert').textContent).toContain('Could not send the extra work (500)'));
  });

  it('reports a failed load, retries, and explains an empty day', async () => {
    const board = serve([]);
    const serveRows = board.load;
    board.load = () => {
      board.load = serveRows;
      return response({}, 503);
    };
    mount();
    expect((await screen.findByRole('alert')).textContent).toContain('Unable to load the live floor');
    await userEvent.setup().click(screen.getByRole('button', { name: 'Try again' }));
    expect(await screen.findByText('No cars are booked at the hub today.')).toBeTruthy();
    await userEvent.setup().click(screen.getByRole('button', { name: 'Refresh board' }));
    await waitFor(() => expect(boardLoads()).toBeGreaterThanOrEqual(3));
  });

  it('refreshes on demand', async () => {
    serve([servicing]);
    mount();
    await screen.findByText('Alex Demo');
    await userEvent.setup().click(screen.getByRole('button', { name: 'Refresh' }));
    await waitFor(() => expect(boardLoads()).toBeGreaterThanOrEqual(2));
  });

  it('keeps the minute count moving between polls', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true, toFake: ['setInterval', 'clearInterval', 'Date'] });
    serve([{ ...servicing, checkedInAt: new Date(Date.now() - 23.5 * 60_000).toISOString() }]);
    mount();
    expect(await screen.findByText('23 of 60 min')).toBeTruthy();
    act(() => {
      vi.advanceTimersByTime(60_000);
    });
    expect(screen.getByText('24 of 60 min')).toBeTruthy();
  });

  it('redraws from a streamed floor event without fetching the board again', async () => {
    const board = serve([servicing]);
    mount();
    await screen.findByText('Alex Demo');
    await waitFor(() => expect(board.streams).toHaveLength(1));
    expect(fetcher).toHaveBeenCalledWith(
      `/v1/hubs/${HUB_ID}/floor/stream`,
      expect.objectContaining({ headers: { Accept: 'text/event-stream' }, signal: expect.any(AbortSignal) }),
    );
    expect(screen.getByText('Polling')).toBeTruthy();
    const loads = boardLoads();

    board.streams[0].send('floor', [{ ...servicing, stage: 'QUALITY_CHECK', nextStages: ['WASHING'] }, arriving]);
    expect(await screen.findByRole('button', { name: 'To wash' })).toBeTruthy();
    expect(screen.getByText('Sam Early')).toBeTruthy();
    expect(screen.getByRole('heading', { name: /Arriving/ }).textContent).toContain('1');
    expect(screen.getByText('Live')).toBeTruthy();
    expect(boardLoads()).toBe(loads);

    // Frames that are not a floor board leave it alone; the next real one still lands.
    board.streams[0].send('insights', [{ ...arriving, customerName: 'Wrong Channel' }]);
    board.streams[0].push('event: floor\ndata: not json\n\n');
    board.streams[0].send('floor', { customerName: 'Not A List' });
    board.streams[0].send('floor', [{ ...servicing, stage: 'WASHING', nextStages: ['READY'] }]);
    expect(await screen.findByRole('button', { name: 'Ready for collection' })).toBeTruthy();
    expect(screen.queryByText('Wrong Channel')).toBeNull();
    expect(screen.queryByText('Not A List')).toBeNull();
    expect(screen.queryByText('Sam Early')).toBeNull();
    expect(boardLoads()).toBe(loads);

    // Leaving the board hangs up the stream.
    cleanup();
    expect(board.streams[0].cancelled).toBe(true);
  });

  it('shows Polling once the stream has been silent for 45 seconds', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true, toFake: ['setTimeout', 'clearTimeout'] });
    const board = serve([servicing]);
    mount();
    await screen.findByText('Alex Demo');
    await waitFor(() => expect(board.streams).toHaveLength(1));
    expect(screen.getByText('Polling')).toBeTruthy();

    // A keepalive proves the connection is alive even when nothing on the floor changed.
    board.streams[0].push(':keepalive\n');
    expect(await screen.findByText('Live')).toBeTruthy();
    act(() => {
      vi.advanceTimersByTime(45_000);
    });
    expect(screen.getByText('Polling')).toBeTruthy();

    board.streams[0].send('floor', [servicing]);
    expect(await screen.findByText('Live')).toBeTruthy();
  });

  it('reconnects with growing delays when the stream drops, and quickly again once it was healthy', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true, toFake: ['setTimeout', 'clearTimeout'] });
    const board = serve([servicing]);
    mount();
    await waitFor(() => expect(board.streams).toHaveLength(1));

    board.streams[0].close();
    await act(() => vi.advanceTimersByTimeAsync(1_000));
    expect(board.streams).toHaveLength(2);

    board.streams[1].close();
    await act(() => vi.advanceTimersByTimeAsync(1_000));
    expect(board.streams).toHaveLength(2);
    await act(() => vi.advanceTimersByTimeAsync(1_000));
    expect(board.streams).toHaveLength(3);

    board.streams[2].send('floor', [servicing]);
    expect(await screen.findByText('Live')).toBeTruthy();
    board.streams[2].close();
    await act(() => vi.advanceTimersByTimeAsync(1_000));
    expect(board.streams).toHaveLength(4);

    // Leaving the board while it waits to reconnect stops the reconnecting too.
    board.streams[3].close();
    await act(() => vi.advanceTimersByTimeAsync(0));
    cleanup();
    await act(() => vi.advanceTimersByTimeAsync(10_000));
    expect(board.streams).toHaveLength(4);
  });
});
