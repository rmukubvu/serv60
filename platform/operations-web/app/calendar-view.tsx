'use client';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useMemo, useState } from 'react';
import { BookingCard, type CalendarEntry } from './booking-card';
import { API_BASE, HUB_ID } from './config';
import { Button } from './ui/button';

function dayBounds() {
  const from = new Date();
  from.setHours(0, 0, 0, 0);
  const to = new Date(from);
  to.setDate(to.getDate() + 1);
  return { from: from.toISOString(), to: to.toISOString() };
}

async function fetchCalendar(): Promise<CalendarEntry[]> {
  const { from, to } = dayBounds();
  const response = await fetch(`${API_BASE}/v1/hubs/${HUB_ID}/calendar?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`);
  if (!response.ok) throw new Error(`Calendar request failed (${response.status})`);
  return response.json() as Promise<CalendarEntry[]>;
}

async function changeHold(id: string, action: 'confirm' | 'cancel') {
  const response = await fetch(`${API_BASE}/v1/visit-holds/${id}/${action}`, { method: 'POST' });
  if (!response.ok) {
    const body = await response.json().catch(() => ({})) as { error?: string };
    throw new Error(body.error ?? `Unable to ${action} appointment (${response.status})`);
  }
  return response.json();
}

function resourceKind(code: string) {
  return code.startsWith('BARBER') || code.startsWith('WORK') ? 'Lifestyle' : 'Automotive';
}

export function CalendarView() {
  const [filter, setFilter] = useState<'All' | 'Automotive' | 'Lifestyle'>('All');
  const queryClient = useQueryClient();
  const calendar = useQuery({ queryKey: ['hub-calendar', HUB_ID], queryFn: fetchCalendar });
  const transition = useMutation({ mutationFn: ({ id, action }: { id: string; action: 'confirm' | 'cancel' }) => changeHold(id, action), onSuccess: () => queryClient.invalidateQueries({ queryKey: ['hub-calendar', HUB_ID] }) });
  const lanes = useMemo(() => {
    const grouped = new Map<string, CalendarEntry[]>();
    for (const entry of calendar.data ?? []) {
      const current = grouped.get(entry.resourceCode) ?? [];
      current.push(entry);
      grouped.set(entry.resourceCode, current);
    }
    return [...grouped.entries()]
      .filter(([code]) => filter === 'All' || resourceKind(code) === filter)
      .map(([code, jobs]) => ({ code, kind: resourceKind(code), jobs }));
  }, [calendar.data, filter]);

  if (calendar.isLoading) return <div className="calendar-state" role="status">Loading today&apos;s live allocations…</div>;
  if (calendar.isError) return <div className="calendar-state error" role="alert">Unable to load the hub calendar. Check the API connection and try again.</div>;
  if (lanes.length === 0) return <div className="calendar-state">No allocations match this filter.</div>;

  return <>
    <fieldset className="filters" aria-label="Calendar filters">
      {(['All', 'Automotive', 'Lifestyle'] as const).map((value) => <Button type="button" key={value} className={filter === value ? 'selected' : ''} onClick={() => setFilter(value)}>{value === 'All' ? 'All resources' : value}</Button>)}
    </fieldset>
    {transition.isError && <p className="form-error" role="alert">{transition.error.message}</p>}
    <div className="calendar">{lanes.map((lane) => <article className="lane" key={lane.code}>
      <header><div><strong>{lane.code}</strong><span>{lane.kind}</span></div><b aria-hidden="true">+</b></header>
      <div className="lane-body">{lane.jobs.map((job) => <BookingCard key={job.allocationId} job={job} actions={job.status === 'HELD' && <div className="job-actions"><Button type="button" disabled={transition.isPending} onClick={() => transition.mutate({ id: job.holdId, action: 'confirm' })}>Confirm</Button><Button type="button" disabled={transition.isPending} onClick={() => transition.mutate({ id: job.holdId, action: 'cancel' })}>Cancel</Button></div>} />)}</div>
    </article>)}</div>
  </>;
}
