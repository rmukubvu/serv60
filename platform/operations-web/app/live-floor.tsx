'use client';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { apiFetch } from './api';
import { openEventStream, type SseEvent } from './event-stream';
import { useHub } from './hub-context';
import { Badge } from './ui/badge';
import { Button } from './ui/button';

export type VisitStage =
  | 'CHECKED_IN'
  | 'INSPECTING'
  | 'SERVICING'
  | 'QUALITY_CHECK'
  | 'WASHING'
  | 'READY'
  | 'HANDED_OVER';

/** The stages in the order a car moves through them. The 60-minute clock runs from CHECKED_IN to READY. */
export const VISIT_STAGES: readonly VisitStage[] = [
  'CHECKED_IN',
  'INSPECTING',
  'SERVICING',
  'QUALITY_CHECK',
  'WASHING',
  'READY',
  'HANDED_OVER',
];

export type ComplimentaryServiceCode = 'BARBER' | 'PRIVATE_WORK' | 'MASSAGE';

export type FloorVisit = {
  visitId: string;
  startsAt: string;
  endsAt: string;
  bookingStatus: string;
  /** null until the car is checked in. */
  stage: VisitStage | null;
  checkedInAt: string | null;
  readyAt: string | null;
  handedOverAt: string | null;
  /** Server-computed; null before check-in and frozen once ready. The board recomputes it client-side. */
  elapsedMinutes: number | null;
  targetMinutes: number;
  customerName: string;
  vehicle: string;
  registration: string | null;
  complimentaryServiceCode: ComplimentaryServiceCode | null;
  bayCode: string | null;
  pendingScopeChanges: number;
  approvedExtrasMinor: number;
  /** Set once the car is handed over; null before. */
  invoiceNumber: string | null;
  /** The invoice total in ZAR cents; null until handed over. */
  invoiceTotalMinor: number | null;
  /** The only stages the backend accepts next; empty once handed over. */
  nextStages: VisitStage[];
};

export type ScopeChangeItem = { description: string; amountMinor: number };

export type ScopeChange = {
  id: string;
  visitId: string;
  status: 'PROPOSED' | 'APPROVED' | 'DECLINED';
  items: ScopeChangeItem[];
  note: string | null;
  totalMinor: number;
  currency: string;
  proposedAt: string;
  decidedAt: string | null;
};

const STAGE_LABELS: Record<VisitStage, string> = {
  CHECKED_IN: 'Checked in',
  INSPECTING: 'Inspecting',
  SERVICING: 'Servicing',
  QUALITY_CHECK: 'Quality check',
  WASHING: 'Washing',
  READY: 'Ready for collection',
  HANDED_OVER: 'Handed over',
};

const NEXT_STAGE_LABELS: Record<VisitStage, string> = {
  CHECKED_IN: 'Check in',
  INSPECTING: 'Start inspection',
  SERVICING: 'Start service',
  QUALITY_CHECK: 'Quality check',
  WASHING: 'To wash',
  READY: 'Ready for collection',
  HANDED_OVER: 'Hand over',
};

const COMPLIMENTARY_LABELS: Record<ComplimentaryServiceCode, string> = {
  BARBER: 'Barber',
  PRIVATE_WORK: 'Private workspace',
  MASSAGE: 'Massage',
};

/** Human label for the stage a car is in. */
export function stageLabel(stage: VisitStage): string {
  return STAGE_LABELS[stage] ?? stage;
}

/** Button text for recording a stage as the next step. */
export function nextStageLabel(stage: VisitStage): string {
  return NEXT_STAGE_LABELS[stage] ?? stage;
}

export function complimentaryLabel(code: ComplimentaryServiceCode | null): string | null {
  if (!code) return null;
  return COMPLIMENTARY_LABELS[code] ?? code;
}

export type FloorGroups = { onFloor: FloorVisit[]; arriving: FloorVisit[]; done: FloorVisit[] };

/** Cars being worked on, cars still to arrive, and cars already collected; server order kept within each. */
export function groupFloor(rows: FloorVisit[]): FloorGroups {
  const groups: FloorGroups = { onFloor: [], arriving: [], done: [] };
  for (const row of rows) {
    if (row.stage == null) groups.arriving.push(row);
    else if (row.stage === 'HANDED_OVER') groups.done.push(row);
    else groups.onFloor.push(row);
  }
  return groups;
}

/**
 * Minutes on the 60-minute clock, counted from the check-in time so the number keeps moving between
 * polls. Frozen at readyAt (or handedOverAt if readyAt is missing); null before check-in.
 */
export function floorMinutes(row: FloorVisit, now: number = Date.now()): number | null {
  if (!row.checkedInAt) return null;
  const started = Date.parse(row.checkedInAt);
  if (Number.isNaN(started)) return row.elapsedMinutes;
  const frozenAt = row.readyAt ?? row.handedOverAt;
  const stoppedAt = frozenAt ? Date.parse(frozenAt) : Number.NaN;
  const end = Number.isNaN(stoppedAt) ? now : stoppedAt;
  return Math.max(0, Math.floor((end - started) / 60_000));
}

/** "23 of 60 min", or "Not checked in" before the clock starts. */
export function elapsedLabel(row: FloorVisit, now: number = Date.now()): string {
  const minutes = floorMinutes(row, now);
  return minutes == null ? 'Not checked in' : `${minutes} of ${row.targetMinutes} min`;
}

export function isOverTarget(row: FloorVisit, now: number = Date.now()): boolean {
  const minutes = floorMinutes(row, now);
  return minutes != null && minutes > row.targetMinutes;
}

const randFormat = new Intl.NumberFormat('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });

/** Minor units (cents) as a rand amount: 145000 -> "R1,450.00". */
export function rand(minor: number): string {
  const sign = minor < 0 ? '-' : '';
  return `${sign}R${randFormat.format(Math.abs(minor) / 100)}`;
}

/** A rand amount typed by staff ("1450.00", "1 450,50") to minor units, rounded to the cent. NaN when unreadable. */
export function randsToMinor(value: string): number {
  const amount = Number(value.replace(/\s/g, '').replace(',', '.'));
  return Number.isFinite(amount) ? Math.round(amount * 100) : Number.NaN;
}

const sastTime = new Intl.DateTimeFormat('en-GB', {
  timeZone: 'Africa/Johannesburg',
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23',
});
const sastClock = new Intl.DateTimeFormat('en-GB', {
  timeZone: 'Africa/Johannesburg',
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  hourCycle: 'h23',
});

/** Wall-clock time at the hub (SAST) as HH:mm; null for missing or unreadable instants. */
export function sastLabel(value: string | number | null): string | null {
  if (value == null) return null;
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? null : sastTime.format(date);
}

async function fetchFloor(hubId: string): Promise<FloorVisit[]> {
  const response = await apiFetch(`/v1/hubs/${hubId}/floor`);
  if (!response.ok) throw new Error(`Live floor request failed (${response.status})`);
  return response.json() as Promise<FloorVisit[]>;
}

/** Safety net behind the stream; it also reconciles the board now and then while the stream is up. */
const POLL_INTERVAL_MS = 30_000;
/** Reconnect delays after the stream drops; the last one repeats. */
const STREAM_BACKOFF_MS = [1_000, 2_000, 5_000, 10_000];
/** The server sends `:keepalive` every 15 s: after three silent ones the board is on the poll alone. */
const LIVE_WINDOW_MS = 45_000;

const LIVE_HINT = 'Connected to the hub: changes appear the moment they happen.';
const POLLING_HINT = 'Not connected to the hub right now: the board refreshes every 30 seconds.';

/** Resolve after `ms`, or straight away once the signal aborts. */
function pause(ms: number, signal: AbortSignal): Promise<void> {
  return new Promise((resolve) => {
    const timer = window.setTimeout(() => {
      signal.removeEventListener('abort', onAbort);
      resolve();
    }, ms);
    function onAbort() {
      window.clearTimeout(timer);
      resolve();
    }
    signal.addEventListener('abort', onAbort, { once: true });
  });
}

/**
 * Keeps the hub's floor stream open and writes every `floor` event into the query cache, so the board redraws
 * the moment something changes. Returns true while the server has spoken within the last 45 s (floor events
 * or keepalives). Reconnects with backoff when the stream drops or is refused; closes it when the hub changes
 * or the board unmounts.
 */
export function useFloorStream(hubId: string): boolean {
  const queryClient = useQueryClient();
  const [liveHub, setLiveHub] = useState<string | null>(null);

  useEffect(() => {
    const controller = new AbortController();
    const { signal } = controller;
    let quietTimer: number | undefined;
    let attempt = 0;

    const heard = () => {
      if (signal.aborted) return;
      attempt = 0;
      setLiveHub(hubId);
      window.clearTimeout(quietTimer);
      quietTimer = window.setTimeout(
        () => setLiveHub((current) => (current === hubId ? null : current)),
        LIVE_WINDOW_MS,
      );
    };

    const apply = ({ event, data }: SseEvent) => {
      if (event !== 'floor' || signal.aborted) return;
      let rows: unknown;
      try {
        rows = JSON.parse(data);
      } catch {
        return; // A malformed frame is not worth dropping the stream for; the next event or poll corrects the board.
      }
      if (Array.isArray(rows)) queryClient.setQueryData<FloorVisit[]>(['live-floor', hubId], rows);
    };

    async function run() {
      while (!signal.aborted) {
        try {
          await openEventStream(`/v1/hubs/${hubId}/floor/stream`, apply, { signal, onActivity: heard });
        } catch {
          // A refused or broken stream is retried below; the poll keeps the board honest meanwhile.
        }
        if (signal.aborted) return;
        await pause(STREAM_BACKOFF_MS[Math.min(attempt, STREAM_BACKOFF_MS.length - 1)], signal);
        attempt += 1;
      }
    }
    run();

    return () => {
      controller.abort();
      window.clearTimeout(quietTimer);
    };
  }, [hubId, queryClient]);

  return liveHub === hubId;
}

/** Backend errors arrive as {"error": "message"}; surface that message to staff verbatim. */
async function failWith(response: Response, fallback: string): Promise<never> {
  const body = (await response.json().catch(() => ({}))) as { error?: string };
  throw new Error(body.error ?? fallback);
}

type AdvanceInput = { visitId: string; stage: VisitStage; note?: string };

/** Check-in has its own endpoint; every later stage goes through /stages. */
async function recordStage(hubId: string, { visitId, stage, note }: AdvanceInput): Promise<FloorVisit | null> {
  const base = `/v1/hubs/${hubId}/visits/${visitId}`;
  const checkIn = stage === 'CHECKED_IN';
  const payload = checkIn ? {} : { stage };
  const response = await apiFetch(checkIn ? `${base}/check-in` : `${base}/stages`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(note ? { ...payload, note } : payload),
  });
  if (!response.ok) {
    await failWith(response, `Could not record "${stageLabel(stage)}" (${response.status})`);
  }
  return (await response.json().catch(() => null)) as FloorVisit | null;
}

type ExtraWorkForm = { description: string; amount: string; note: string };

/** Demo defaults so the presenter can send a proposal in one click. */
const DEMO_EXTRA_WORK: ExtraWorkForm = {
  description: 'Front brake pads',
  amount: '1450.00',
  note: 'Pads at 2 mm, photos attached',
};

type ProposalInput = ExtraWorkForm & { visitId: string };

async function proposeExtraWork(hubId: string, input: ProposalInput): Promise<ScopeChange> {
  const amountMinor = randsToMinor(input.amount);
  if (!Number.isFinite(amountMinor) || amountMinor < 0) {
    throw new Error('Enter the amount in rands, for example 1450.00.');
  }
  const description = input.description.trim();
  if (!description) throw new Error('Describe the extra work before sending it to the customer.');
  const note = input.note.trim();
  const response = await apiFetch(`/v1/hubs/${hubId}/visits/${input.visitId}/scope-changes`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ items: [{ description, amountMinor }], ...(note ? { note } : {}) }),
  });
  if (!response.ok) await failWith(response, `Could not send the extra work (${response.status})`);
  return response.json() as Promise<ScopeChange>;
}

type CardState = 'arriving' | 'floor' | 'over' | 'ready' | 'done';

function cardState(row: FloorVisit, over: boolean): CardState {
  if (row.stage == null) return 'arriving';
  if (row.stage === 'HANDED_OVER') return 'done';
  if (over) return 'over';
  if (row.stage === 'READY') return 'ready';
  return 'floor';
}

function stageBadgeVariant(state: CardState) {
  if (state === 'ready') return 'success' as const;
  if (state === 'done') return 'secondary' as const;
  if (state === 'over') return 'warning' as const;
  return 'outline' as const;
}

type FloorCardProps = {
  row: FloorVisit;
  now: number;
  busy: boolean;
  error: string | null;
  extraOpen: boolean;
  extra: ExtraWorkForm;
  extraBusy: boolean;
  extraError: string | null;
  onAdvance: (stage: VisitStage) => void;
  onToggleExtra: () => void;
  onExtraChange: (next: ExtraWorkForm) => void;
  onProposeExtra: () => void;
};

function FloorCard({
  row,
  now,
  busy,
  error,
  extraOpen,
  extra,
  extraBusy,
  extraError,
  onAdvance,
  onToggleExtra,
  onExtraChange,
  onProposeExtra,
}: FloorCardProps) {
  const next: VisitStage | undefined = row.nextStages[0];
  const minutes = floorMinutes(row, now);
  const over = isOverTarget(row, now);
  const state = cardState(row, over);
  const currentIndex = row.stage ? VISIT_STAGES.indexOf(row.stage) : -1;
  const canPropose = row.stage != null && row.stage !== 'READY' && row.stage !== 'HANDED_OVER';
  const complimentary = complimentaryLabel(row.complimentaryServiceCode);
  const clock = row.readyAt
    ? `Ready ${sastLabel(row.readyAt)}`
    : row.checkedInAt
      ? `Checked in ${sastLabel(row.checkedInAt)}`
      : `Due ${sastLabel(row.startsAt)}`;

  return (
    <article className="dispatch-card floor-card" data-state={state} aria-label={`${row.customerName}, ${row.vehicle}`}>
      <div className="dispatch-card-heading floor-card-heading">
        <div>
          <strong>{row.customerName}</strong>
          <span>{`${row.vehicle} · ${row.registration ?? 'No registration'}`}</span>
          <span>
            {row.bayCode ? `Bay ${row.bayCode}` : 'No bay yet'} · Booked {sastLabel(row.startsAt) ?? '—'} SAST
            {complimentary ? ` · ${complimentary}` : ''}
          </span>
        </div>
        <div className="floor-card-badges">
          <Badge variant={stageBadgeVariant(state)}>{row.stage ? stageLabel(row.stage) : 'Not checked in'}</Badge>
          {row.pendingScopeChanges > 0 ? (
            <Badge variant="warning">{row.pendingScopeChanges} awaiting customer</Badge>
          ) : null}
        </div>
      </div>

      <ol className="floor-track" aria-label="Stage progress">
        {VISIT_STAGES.map((stage, index) => {
          const chip = index < currentIndex ? 'done' : index === currentIndex ? 'current' : 'upcoming';
          return (
            <li key={stage} data-state={chip} aria-current={chip === 'current' ? 'step' : undefined}>
              <i aria-hidden="true">{chip === 'done' ? '✓' : chip === 'current' ? '●' : '○'}</i>
              {stageLabel(stage)}
            </li>
          );
        })}
      </ol>

      <div className="floor-meta">
        {minutes == null ? (
          <span className="floor-elapsed floor-elapsed-pending">Not checked in yet</span>
        ) : (
          <span className={over ? 'floor-elapsed floor-elapsed-over' : 'floor-elapsed'}>
            {elapsedLabel(row, now)}
            {over ? <Badge variant="warning">Over target</Badge> : null}
          </span>
        )}
        <span className="floor-times">
          {clock}
          {row.handedOverAt ? ` · Handed over ${sastLabel(row.handedOverAt)}` : ''}
        </span>
        {row.stage === 'HANDED_OVER' && row.invoiceNumber ? (
          <span className="floor-invoice">
            Invoice {row.invoiceNumber}
            {row.invoiceTotalMinor != null ? ` · ${rand(row.invoiceTotalMinor)}` : ''}
          </span>
        ) : row.approvedExtrasMinor > 0 ? (
          <span className="floor-extras">Approved extras {rand(row.approvedExtrasMinor)}</span>
        ) : null}
      </div>

      {error ? (
        <p className="form-error" role="alert">
          {error}
        </p>
      ) : null}

      {next || canPropose ? (
        <div className="dispatch-actions floor-actions">
          {next ? (
            <Button className="primary" type="button" disabled={busy} onClick={() => onAdvance(next)}>
              {busy ? 'Recording…' : nextStageLabel(next)}
            </Button>
          ) : null}
          {canPropose ? (
            <Button type="button" variant="outline" aria-expanded={extraOpen} onClick={onToggleExtra}>
              Propose extra work
            </Button>
          ) : null}
        </div>
      ) : null}

      {extraOpen ? (
        <form
          className="dispatch-form floor-form"
          onSubmit={(event) => {
            event.preventDefault();
            onProposeExtra();
          }}
        >
          <p className="dispatch-why">
            The customer approves or declines this on their phone. The car keeps its stage while they decide.
          </p>
          <label>
            Work description
            <input
              required
              value={extra.description}
              onChange={(event) => onExtraChange({ ...extra, description: event.target.value })}
            />
          </label>
          <div className="dialog-row">
            <label>
              Amount (rands)
              <input
                required
                type="number"
                min="0"
                step="0.01"
                inputMode="decimal"
                value={extra.amount}
                onChange={(event) => onExtraChange({ ...extra, amount: event.target.value })}
              />
            </label>
          </div>
          <label>
            Note for the customer
            <input value={extra.note} onChange={(event) => onExtraChange({ ...extra, note: event.target.value })} />
          </label>
          {extraError ? (
            <p className="form-error" role="alert">
              {extraError}
            </p>
          ) : null}
          <div className="dispatch-actions">
            <Button className="primary" type="submit" disabled={extraBusy}>
              {extraBusy ? 'Sending…' : 'Send to customer'}
            </Button>
            <Button type="button" variant="ghost" onClick={onToggleExtra}>
              Cancel
            </Button>
          </div>
        </form>
      ) : null}
    </article>
  );
}

const GROUPS: { key: keyof FloorGroups; title: string; empty: string }[] = [
  { key: 'onFloor', title: 'On the floor', empty: 'No cars on the floor right now.' },
  { key: 'arriving', title: 'Arriving', empty: 'Every booked car has been checked in.' },
  { key: 'done', title: 'Handed over', empty: 'No cars handed over yet today.' },
];

export function LiveFloor() {
  const queryClient = useQueryClient();
  const { hubId } = useHub();
  const [extraFor, setExtraFor] = useState<string | null>(null);
  const [extra, setExtra] = useState<ExtraWorkForm>(DEMO_EXTRA_WORK);
  const [, setTick] = useState(0);
  const floor = useQuery({
    queryKey: ['live-floor', hubId],
    queryFn: () => fetchFloor(hubId),
    refetchInterval: POLL_INTERVAL_MS,
  });
  const live = useFloorStream(hubId);
  const rows = floor.data ?? [];
  const ticking = rows.some((row) => row.checkedInAt && !row.readyAt && !row.handedOverAt);

  // Elapsed labels are derived from check-in time, so a one-second tick keeps them moving between polls.
  useEffect(() => {
    if (!ticking) return;
    const timer = window.setInterval(() => setTick((value) => value + 1), 1_000);
    return () => window.clearInterval(timer);
  }, [ticking]);

  function refreshFloor() {
    queryClient.invalidateQueries({ queryKey: ['live-floor'] });
    queryClient.invalidateQueries({ queryKey: ['ops-insights'] });
  }

  const advance = useMutation({
    mutationFn: (input: AdvanceInput) => recordStage(hubId, input),
    onSuccess: (visit) => {
      // Show the backend's view of the car straight away; the refetch confirms it.
      if (visit?.visitId) {
        queryClient.setQueryData<FloorVisit[]>(['live-floor', hubId], (current) =>
          current?.map((row) => (row.visitId === visit.visitId ? visit : row)),
        );
      }
      refreshFloor();
    },
  });

  const propose = useMutation({
    mutationFn: (input: ProposalInput) => proposeExtraWork(hubId, input),
    onSuccess: () => {
      setExtraFor(null);
      setExtra(DEMO_EXTRA_WORK);
      refreshFloor();
    },
  });

  function toggleExtra(visitId: string) {
    propose.reset();
    setExtra(DEMO_EXTRA_WORK);
    setExtraFor((current) => (current === visitId ? null : visitId));
  }

  if (floor.isLoading)
    return (
      <div className="calendar-state" role="status">
        Loading the live floor…
      </div>
    );
  if (floor.isError)
    return (
      <div className="calendar-state error" role="alert">
        Unable to load the live floor. Check the API connection and try again.
        <br />
        <Button type="button" onClick={() => floor.refetch()}>
          Try again
        </Button>
      </div>
    );
  if (rows.length === 0)
    return (
      <div className="calendar-state">
        No cars are booked at the hub today.
        <br />
        <Button type="button" onClick={() => floor.refetch()}>
          Refresh board
        </Button>
      </div>
    );

  const now = Date.now();
  const groups = groupFloor(rows);
  const advancing = advance.variables?.visitId;
  const proposing = propose.variables?.visitId;

  return (
    <div className="floor-board">
      <div className="dispatch-toolbar floor-toolbar">
        <span>
          <Badge variant="secondary">{groups.onFloor.length} on the floor</Badge>
          {` · ${groups.arriving.length} arriving · ${groups.done.length} handed over`}
          {floor.dataUpdatedAt ? ` · updated ${sastClock.format(floor.dataUpdatedAt)} SAST` : ''}
          {' · '}
          <Badge variant={live ? 'success' : 'outline'} title={live ? LIVE_HINT : POLLING_HINT}>
            {live ? 'Live' : 'Polling'}
          </Badge>
        </span>
        <Button type="button" variant="outline" size="sm" onClick={() => floor.refetch()}>
          Refresh
        </Button>
      </div>
      {GROUPS.map((group) => {
        const cars = groups[group.key];
        return (
          <section className="floor-group" key={group.key} aria-label={group.title}>
            <h3 className="floor-group-heading">
              {group.title}
              <Badge variant="outline">{cars.length}</Badge>
            </h3>
            {cars.length === 0 ? (
              <p className="floor-group-empty">{group.empty}</p>
            ) : (
              <div className="floor-cards">
                {cars.map((row) => (
                  <FloorCard
                    key={row.visitId}
                    row={row}
                    now={now}
                    busy={advance.isPending && advancing === row.visitId}
                    error={advance.isError && advancing === row.visitId ? advance.error.message : null}
                    extraOpen={extraFor === row.visitId}
                    extra={extra}
                    extraBusy={propose.isPending && proposing === row.visitId}
                    extraError={propose.isError && proposing === row.visitId ? propose.error.message : null}
                    onAdvance={(stage) => advance.mutate({ visitId: row.visitId, stage })}
                    onToggleExtra={() => toggleExtra(row.visitId)}
                    onExtraChange={setExtra}
                    onProposeExtra={() => propose.mutate({ visitId: row.visitId, ...extra })}
                  />
                ))}
              </div>
            )}
          </section>
        );
      })}
    </div>
  );
}
