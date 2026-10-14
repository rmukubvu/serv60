'use client';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useMemo, useState } from 'react';
import { BookingCard, type CalendarEntry } from './booking-card';
import { API_BASE } from './config';
import { useHub } from './hub-context';
import { useHubSettings } from './hub-settings-api';
import { Button } from './ui/button';

function johannesburgDay(date: Date) {
  return new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Africa/Johannesburg',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(date);
}

function dayBounds(day: string) {
  const from = new Date(`${day}T00:00:00+02:00`);
  const to = new Date(from.getTime() + 86_400_000);
  return { from: from.toISOString(), to: to.toISOString() };
}

function shiftDay(day: string, delta: number) {
  const date = new Date(`${day}T12:00:00+02:00`);
  date.setDate(date.getDate() + delta);
  return johannesburgDay(date);
}

async function fetchCalendar(hubId: string, day: string): Promise<CalendarEntry[]> {
  const { from, to } = dayBounds(day);
  const response = await fetch(
    `${API_BASE}/v1/hubs/${hubId}/calendar?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`,
  );
  if (!response.ok) throw new Error(`Calendar request failed (${response.status})`);
  return response.json() as Promise<CalendarEntry[]>;
}

async function changeHold(id: string, action: 'confirm' | 'cancel') {
  const response = await fetch(`${API_BASE}/v1/visit-holds/${id}/${action}`, { method: 'POST' });
  if (!response.ok) {
    const body = (await response.json().catch(() => ({}))) as { error?: string };
    throw new Error(body.error ?? `Unable to ${action} appointment (${response.status})`);
  }
  return response.json();
}

function resourceKind(code: string, kind?: string) {
  if (kind) return ['BARBER_CHAIR', 'MASSAGE_ROOM', 'WORKSPACE'].includes(kind) ? 'Lifestyle' : 'Automotive';
  return code.startsWith('BARBER') || code.startsWith('WORK') || code.startsWith('MASSAGE')
    ? 'Lifestyle'
    : 'Automotive';
}

/** Floor order: express bays (numeric), then repair/extras, then lifestyle. */
function laneGroup(code: string, kind?: string): number {
  const lifestyle =
    kind === 'BARBER_CHAIR' ||
    kind === 'MASSAGE_ROOM' ||
    kind === 'WORKSPACE' ||
    code.startsWith('BARBER') ||
    code.startsWith('WORK') ||
    code.startsWith('MASSAGE');
  if (lifestyle) return 2;
  if (kind === 'EXPRESS_BAY' || code.startsWith('EXPRESS')) return 0;
  return 1; // repair / extras / other automotive
}

function naturalCode(code: string): [string, number, string] {
  const match = code.match(/^(.*?)(\d+)(.*)$/);
  if (!match) return [code, Number.MAX_SAFE_INTEGER, ''];
  return [match[1], Number(match[2]), match[3]];
}

function compareLanes(
  left: { code: string; kind?: string },
  right: { code: string; kind?: string },
): number {
  const groupDelta = laneGroup(left.code, left.kind) - laneGroup(right.code, right.kind);
  if (groupDelta !== 0) return groupDelta;
  const [leftPrefix, leftNum, leftSuffix] = naturalCode(left.code);
  const [rightPrefix, rightNum, rightSuffix] = naturalCode(right.code);
  const prefixDelta = leftPrefix.localeCompare(rightPrefix);
  if (prefixDelta !== 0) return prefixDelta;
  if (leftNum !== rightNum) return leftNum - rightNum;
  return leftSuffix.localeCompare(rightSuffix) || left.code.localeCompare(right.code);
}

export function CalendarView() {
  const { hubId } = useHub();
  const [filter, setFilter] = useState<'All' | 'Automotive' | 'Lifestyle'>('All');
  const [statusFilter, setStatusFilter] = useState<'All' | 'HELD' | 'CONFIRMED'>('All');
  const [day, setDay] = useState(() => johannesburgDay(new Date()));
  const queryClient = useQueryClient();
  const settings = useHubSettings();
  const calendar = useQuery({
    queryKey: ['hub-calendar', hubId, day],
    queryFn: () => fetchCalendar(hubId, day),
  });
  const transition = useMutation({
    mutationFn: ({ id, action }: { id: string; action: 'confirm' | 'cancel' }) => changeHold(id, action),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['hub-calendar', hubId] }),
  });
  const lanes = useMemo(() => {
    const grouped = new Map<string, CalendarEntry[]>();
    for (const resource of settings.data?.resources ?? []) grouped.set(resource.code, []);
    for (const entry of calendar.data ?? []) {
      if (statusFilter !== 'All' && entry.status !== statusFilter) continue;
      const current = grouped.get(entry.resourceCode) ?? [];
      current.push(entry);
      grouped.set(entry.resourceCode, current);
    }
    const resourceMeta = (code: string) =>
      settings.data?.resources.find((resource) => resource.code === code);
    const kindOf = (code: string) => resourceKind(code, resourceMeta(code)?.kind);
    return [...grouped.entries()]
      .filter(([code]) => filter === 'All' || kindOf(code) === filter)
      .map(([code, jobs]) => ({
        code,
        apiKind: resourceMeta(code)?.kind,
        kind: kindOf(code),
        jobs,
      }))
      .sort((left, right) =>
        compareLanes({ code: left.code, kind: left.apiKind }, { code: right.code, kind: right.apiKind }),
      );
  }, [calendar.data, settings.data, filter, statusFilter]);

  if (calendar.isLoading || settings.isLoading)
    return <div className="calendar-state" role="status">Loading live allocations…</div>;
  if (calendar.isError || settings.isError)
    return (
      <div className="calendar-state error" role="alert">
        Unable to load the hub calendar. Check the API connection and try again.
      </div>
    );

  return (
    <>
      <div className="calendar-toolbar">
        <div className="day-nav">
          <Button type="button" variant="outline" size="sm" onClick={() => setDay((value) => shiftDay(value, -1))}>
            Previous
          </Button>
          <strong>{day}</strong>
          <Button type="button" variant="outline" size="sm" onClick={() => setDay((value) => shiftDay(value, 1))}>
            Next
          </Button>
          <Button type="button" variant="ghost" size="sm" onClick={() => setDay(johannesburgDay(new Date()))}>
            Today
          </Button>
        </div>
      </div>
      <fieldset className="filters" aria-label="Calendar filters">
        {(['All', 'Automotive', 'Lifestyle'] as const).map((value) => (
          <Button
            type="button"
            key={value}
            className={filter === value ? 'selected' : ''}
            onClick={() => setFilter(value)}
          >
            {value === 'All' ? 'All resources' : value}
          </Button>
        ))}
        {(['All', 'HELD', 'CONFIRMED'] as const).map((value) => (
          <Button
            type="button"
            key={`status-${value}`}
            className={statusFilter === value ? 'selected' : ''}
            onClick={() => setStatusFilter(value)}
          >
            {value === 'All' ? 'All statuses' : value === 'HELD' ? 'Held' : 'Confirmed'}
          </Button>
        ))}
      </fieldset>
      {transition.isError && (
        <p className="form-error" role="alert">
          {transition.error.message}
        </p>
      )}
      {lanes.length === 0 && <div className="calendar-state">No resources match this filter.</div>}
      <div className="calendar">
        {lanes.map((lane) => (
          <article className="lane" key={lane.code}>
            <header>
              <div>
                <strong>{lane.code}</strong>
                <span>{lane.kind}</span>
              </div>
            </header>
            <div className="lane-body">
              {lane.jobs.length === 0 && <p className="empty-lane">No bookings this day</p>}
              {lane.jobs.map((job) => (
                <BookingCard
                  key={job.allocationId}
                  job={job}
                  actions={
                    (job.status === 'HELD' || job.status === 'CONFIRMED') && (
                      <div className="job-actions">
                        {job.status === 'HELD' && (
                          <Button
                            type="button"
                            disabled={transition.isPending}
                            onClick={() => transition.mutate({ id: job.holdId, action: 'confirm' })}
                          >
                            Confirm
                          </Button>
                        )}
                        <Button
                          type="button"
                          disabled={transition.isPending}
                          onClick={() => transition.mutate({ id: job.holdId, action: 'cancel' })}
                        >
                          Cancel
                        </Button>
                      </div>
                    )
                  }
                />
              ))}
            </div>
          </article>
        ))}
      </div>
    </>
  );
}
