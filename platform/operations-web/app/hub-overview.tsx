'use client';

import { useQuery } from '@tanstack/react-query';
import { apiFetch } from './api';
import { useHub } from './hub-context';
import { useHubSettings, weekdays } from './hub-settings-api';
import { Badge } from './ui/badge';

async function fetchTodayCalendar(hubId: string) {
  const today = new Intl.DateTimeFormat('en-CA', {
    timeZone: 'Africa/Johannesburg',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).format(new Date());
  const from = new Date(`${today}T00:00:00+02:00`);
  const to = new Date(from.getTime() + 86_400_000);
  const response = await apiFetch(`/v1/hubs/${hubId}/calendar?from=${encodeURIComponent(from.toISOString())}&to=${encodeURIComponent(to.toISOString())}`,
  );
  if (!response.ok) throw new Error(`Calendar request failed (${response.status})`);
  return response.json() as Promise<{ holdId: string }[]>;
}

export function HubOverview() {
  const { hubId } = useHub();
  const settings = useHubSettings();
  const calendar = useQuery({
    queryKey: ['hub-calendar', hubId, 'overview'],
    queryFn: () => fetchTodayCalendar(hubId),
  });
  if (settings.isPending) return <p role="status">Loading hub overview…</p>;
  if (settings.isError)
    return (
      <p className="form-error" role="alert">
        Hub configuration is unavailable. Open settings to retry.
      </p>
    );
  const hub = settings.data;
  const today = new Intl.DateTimeFormat('en-GB', {
    weekday: 'long',
    timeZone: hub.timezone,
  }).format(new Date());
  const hours = hub.tradingHours.find((day) => weekdays[day.dayOfWeek - 1] === today);
  const scheduled = calendar.isError
    ? '—'
    : calendar.data
      ? new Set(calendar.data.map((entry) => entry.holdId)).size
      : '…';
  const complimentary =
    hub.capacity.barberChairs + hub.capacity.massageRooms + hub.capacity.workspaces;
  const trading =
    hours && !hours.closed
      ? `${hours.opensAt.slice(0, 5)}–${hours.closesAt.slice(0, 5)}`
      : 'Closed';

  const metrics = [
    {
      label: 'Scheduled today',
      value: scheduled,
      note: 'Live appointments',
      badge: calendar.isError ? (
        <Badge variant="destructive">Offline</Badge>
      ) : (
        <Badge variant="outline">Live</Badge>
      ),
    },
    {
      label: 'Express bays',
      value: hub.capacity.expressBays,
      note: 'Cars that can be serviced together',
      badge: <Badge variant="outline">Floor</Badge>,
    },
    {
      label: 'Complimentary places',
      value: complimentary,
      note: 'Barber, massage & workspace',
      badge: <Badge variant="outline">Lifestyle</Badge>,
    },
    {
      label: `${today} trading`,
      value: trading,
      note: hub.timezone,
      badge:
        hours && !hours.closed ? (
          <Badge variant="outline">Open</Badge>
        ) : (
          <Badge variant="secondary">Closed</Badge>
        ),
    },
  ];

  return (
    <>
      <div className="context-strip">
        <span>{hub.name}</span>
        <span>
          {hub.published ? 'Published' : 'Unpublished'} · {hub.timezone}
        </span>
      </div>
      <section className="summary" aria-label="Hub summary">
        {metrics.map((metric) => (
          <article className="metric-card" key={metric.label}>
            <div className="metric-label">
              <span>{metric.label}</span>
              {metric.badge}
            </div>
            <strong>{metric.value}</strong>
            <small>{metric.note}</small>
          </article>
        ))}
      </section>
    </>
  );
}
