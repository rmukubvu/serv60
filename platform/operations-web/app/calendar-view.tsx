'use client';

import { useQuery } from '@tanstack/react-query';
import { useMemo, useState } from 'react';
import { Button } from './ui/button';

type CalendarEntry = {
  allocationId: string;
  resourceCode: string;
  serviceCode: string;
  startsAt: string;
  status: 'HELD' | 'CONFIRMED';
};

const HUB_ID = '00000000-0000-0000-0000-000000000060';
const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://localhost:8080';

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

function displayTime(value: string) {
  return new Intl.DateTimeFormat('en-ZA', { hour: '2-digit', minute: '2-digit' }).format(new Date(value));
}

function resourceKind(code: string) {
  return code.startsWith('BARBER') || code.startsWith('WORK') ? 'Lifestyle' : 'Automotive';
}

export function CalendarView() {
  const [filter, setFilter] = useState<'All' | 'Automotive' | 'Lifestyle'>('All');
  const calendar = useQuery({ queryKey: ['hub-calendar', HUB_ID], queryFn: fetchCalendar });
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
      {(['All', 'Automotive', 'Lifestyle'] as const).map((value) => <Button key={value} className={filter === value ? 'selected' : ''} onClick={() => setFilter(value)}>{value === 'All' ? 'All resources' : value}</Button>)}
    </fieldset>
    <div className="calendar">{lanes.map((lane) => <article className="lane" key={lane.code}>
      <header><div><strong>{lane.code}</strong><span>{lane.kind}</span></div><b aria-hidden="true">+</b></header>
      <div className="lane-body">{lane.jobs.map((job) => <div className={`job ${job.status.toLowerCase()}`} key={job.allocationId}>
        <time>{displayTime(job.startsAt)}</time><div><strong>{job.serviceCode.replaceAll('_', ' ')}</strong><span>{job.status === 'HELD' ? 'Held for customer' : 'Confirmed'}</span></div>
      </div>)}</div>
    </article>)}</div>
  </>;
}
