'use client';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useMemo, useState } from 'react';

import { useOpsInsights, type DispatchPriority } from './floor-insights';
import { useHub } from './hub-context';
import { Badge } from './ui/badge';
import { Button } from './ui/button';
import { apiFetch } from './api';

type BoardItem = {
  incident: { id: string; status: string; location: string; description: string; createdAt: string };
  latitude: number | null;
  longitude: number | null;
  truckLabel: string | null;
  truckLatitude: number | null;
  truckLongitude: number | null;
  etaMinutes: number | null;
  rideStatus: string | null;
  rideHomeRequested: boolean;
};

type Assignment = {
  provider: string;
  quoteReference: string;
  amountMinor: string;
  currency: string;
  estimatedMinutes: string;
  rideHome: boolean;
};

const emptyAssignment: Assignment = {
  provider: '',
  quoteReference: '',
  amountMinor: '',
  currency: 'ZAR',
  estimatedMinutes: '',
  rideHome: false,
};

// Incidents are scoped to the hub they are towed to, so every request names the selected hub.
async function fetchBoard(hubId: string): Promise<BoardItem[]> {
  const scope = `hubId=${encodeURIComponent(hubId)}&limit=50`;
  const response = await apiFetch(`/v1/roadside/incidents/dispatch/board?${scope}`);
  if (response.ok) return response.json() as Promise<BoardItem[]>;
  const fallback = await apiFetch(`/v1/roadside/incidents/dispatch?${scope}`);
  if (!fallback.ok) throw new Error(`Dispatch queue request failed (${fallback.status})`);
  const incidents = (await fallback.json()) as BoardItem['incident'][];
  return incidents.map((incident) => ({
    incident,
    latitude: null,
    longitude: null,
    truckLabel: null,
    truckLatitude: null,
    truckLongitude: null,
    etaMinutes: null,
    rideStatus: null,
    rideHomeRequested: false,
  }));
}

async function assignIncident({ id, assignment }: { id: string; assignment: Assignment }) {
  const response = await apiFetch(`/v1/roadside/incidents/${id}/assignment`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      ...assignment,
      amountMinor: Number(assignment.amountMinor),
      estimatedMinutes: Number(assignment.estimatedMinutes),
    }),
  });
  if (!response.ok) {
    const body = (await response.json().catch(() => ({}))) as { error?: string };
    throw new Error(body.error ?? `Assignment failed (${response.status})`);
  }
  return response.json();
}

async function postIncident(id: string, action: string, body?: unknown) {
  const response = await apiFetch(`/v1/roadside/incidents/${id}/${action}`, {
    method: 'POST',
    headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!response.ok) {
    const failure = (await response.json().catch(() => ({}))) as { error?: string };
    throw new Error(failure.error ?? `Roadside update failed (${response.status})`);
  }
  return response.json();
}

type Step = { label: string; status: string };

/** The progress a dispatcher may record next, mirroring the backend's allowed transitions. */
export function nextSteps(status: string): Step[] {
  switch (status) {
    case 'ASSIGNED':
      return [
        { label: 'Truck on the way', status: 'EN_ROUTE' },
        { label: 'Truck arrived', status: 'ON_SCENE' },
      ];
    case 'EN_ROUTE':
      return [{ label: 'Truck arrived', status: 'ON_SCENE' }];
    case 'ON_SCENE':
      return [
        { label: 'Towing', status: 'TOWING' },
        { label: 'Completed', status: 'COMPLETED' },
      ];
    case 'TOWING':
      return [{ label: 'Completed', status: 'COMPLETED' }];
    default:
      return [];
  }
}

function ageLabel(createdAt: string) {
  const minutes = Math.max(0, Math.round((Date.now() - new Date(createdAt).getTime()) / 60000));
  return minutes === 0 ? 'Just now' : `${minutes} min waiting`;
}

export function RoadsideDispatch() {
  const queryClient = useQueryClient();
  const { hubId } = useHub();
  const [selected, setSelected] = useState<string | null>(null);
  const [assignment, setAssignment] = useState<Assignment>(emptyAssignment);
  const [etaById, setEtaById] = useState<Record<string, string>>({});
  const queue = useQuery({
    queryKey: ['roadside-dispatch-board', hubId],
    queryFn: () => fetchBoard(hubId),
    refetchInterval: 15_000,
  });
  const insights = useOpsInsights();
  const priorityById = useMemo(() => {
    const map = new Map<string, DispatchPriority>();
    for (const row of insights.data?.dispatchPriority ?? []) {
      map.set(row.incidentId, row);
    }
    return map;
  }, [insights.data]);
  const rankedBoard = useMemo(() => {
    const items = [...(queue.data ?? [])];
    items.sort((a, b) => {
      const left = priorityById.get(a.incident.id)?.priorityScore ?? 0;
      const right = priorityById.get(b.incident.id)?.priorityScore ?? 0;
      return right - left;
    });
    return items;
  }, [queue.data, priorityById]);
  const assign = useMutation({
    mutationFn: assignIncident,
    onSuccess: () => {
      setSelected(null);
      setAssignment(emptyAssignment);
      queryClient.invalidateQueries({ queryKey: ['roadside-dispatch-board'] });
      queryClient.invalidateQueries({ queryKey: ['ops-insights'] });
    },
  });

  const update = useMutation({
    mutationFn: ({ id, action, body }: { id: string; action: string; body?: unknown }) =>
      postIncident(id, action, body),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['roadside-dispatch-board'] });
      queryClient.invalidateQueries({ queryKey: ['ops-insights'] });
    },
  });

  const markers = useMemo(
    () =>
      rankedBoard.flatMap((item) => {
        const points: { id: string; lat: number; lon: number; kind: string }[] = [];
        if (item.latitude != null && item.longitude != null) {
          points.push({ id: `${item.incident.id}-pin`, lat: item.latitude, lon: item.longitude, kind: 'customer' });
        }
        if (item.truckLatitude != null && item.truckLongitude != null) {
          points.push({ id: `${item.incident.id}-truck`, lat: item.truckLatitude, lon: item.truckLongitude, kind: 'truck' });
        }
        return points;
      }),
    [rankedBoard],
  );

  function openAssign(item: BoardItem) {
    const suggestion = priorityById.get(item.incident.id);
    setSelected(selected === item.incident.id ? null : item.incident.id);
    setAssignment({
      ...emptyAssignment,
      provider: suggestion?.suggestedProvider ?? '',
      estimatedMinutes:
        suggestion?.suggestedEtaMinutes != null
          ? String(suggestion.suggestedEtaMinutes)
          : '',
      rideHome: item.rideHomeRequested,
    });
  }

  if (queue.isLoading) return <div className="calendar-state" role="status">Loading dispatch board…</div>;
  if (queue.isError)
    return (
      <div className="calendar-state error" role="alert">
        Unable to load roadside dispatch board.
      </div>
    );
  if ((queue.data ?? []).length === 0)
    return (
      <div className="calendar-state">
        No roadside incidents are waiting for dispatch.
        <br />
        <Button type="button" onClick={() => queue.refetch()}>
          Refresh board
        </Button>
      </div>
    );

  return (
    <div className="dispatch-list">
      <div className="dispatch-toolbar">
        <span>
          <Badge variant="secondary">{rankedBoard.length} active</Badge>
          {' · ranked by urgency · '}
          {markers.length} map pins
        </span>
        <Button
          type="button"
          variant="outline"
          size="sm"
          onClick={() => queue.refetch()}
          disabled={queue.isFetching}
        >
          {queue.isFetching ? 'Refreshing…' : 'Refresh'}
        </Button>
      </div>
      <div className="dispatch-map" aria-label="Incident and truck positions">
        {rankedBoard.map((item) => (
          <div className="dispatch-map-row" key={`map-${item.incident.id}`}>
            <strong>{item.incident.location}</strong>
            <span>
              {item.latitude != null
                ? `${item.latitude.toFixed(3)}, ${item.longitude?.toFixed(3)}`
                : 'No pin yet'}
              {item.truckLabel ? ` · ${item.truckLabel}` : ''}
              {item.etaMinutes != null ? ` · ETA ${item.etaMinutes}m` : ''}
            </span>
          </div>
        ))}
      </div>
      {assign.isError && (
        <p className="form-error" role="alert">
          {assign.error.message}
        </p>
      )}
      {update.isError && (
        <p className="form-error" role="alert">
          {update.error.message}
        </p>
      )}
      {rankedBoard.map((item, index) => {
        const priority = priorityById.get(item.incident.id);
        const urgency = index === 0 ? 'high' : index < 3 ? 'mid' : 'low';
        return (
        <article className="dispatch-card" data-urgency={urgency} key={item.incident.id}>
          <div className="dispatch-card-heading">
            <div>
              <strong>{item.incident.location}</strong>
              <span>
                {ageLabel(item.incident.createdAt)} · {item.incident.id.slice(0, 8)}
                {priority ? ` · score ${priority.priorityScore}` : ''}
              </span>
            </div>
            <Badge variant="outline" className={`dispatch-status dispatch-status-${item.incident.status.toLowerCase()}`}>
              <span className="status-dot dispatch-status-dot" aria-hidden="true" />
              {item.incident.status}
            </Badge>
          </div>
          <p>{item.incident.description}</p>
          {priority ? <p className="dispatch-why">{priority.reason}</p> : null}
          <div className="dispatch-legs">
            <span>
              Tow · {item.truckLabel ?? 'unassigned'}
              {item.etaMinutes != null ? ` · ${item.etaMinutes} min` : ''}
            </span>
            <span>
              Ride ·{' '}
              {item.rideHomeRequested
                ? item.rideStatus ?? 'requested'
                : 'not requested'}
            </span>
          </div>
          {item.incident.status === 'SEARCHING' ? (
            <form
              className="dispatch-form"
              onSubmit={(event) => {
                event.preventDefault();
                const eta = etaById[item.incident.id] ?? String(priority?.suggestedEtaMinutes ?? 15);
                update.mutate({ id: item.incident.id, action: 'dispatch', body: { etaMinutes: Number(eta) } });
              }}
            >
              <p className="dispatch-why">
                The customer accepted a quote. Call the partner, then confirm the truck is on its way.
              </p>
              <label>
                ETA (minutes)
                <input
                  required
                  min="1"
                  max="240"
                  type="number"
                  value={etaById[item.incident.id] ?? String(priority?.suggestedEtaMinutes ?? 15)}
                  onChange={(event) => setEtaById({ ...etaById, [item.incident.id]: event.target.value })}
                />
              </label>
              <Button className="primary" type="submit" disabled={update.isPending}>
                Confirm truck dispatched
              </Button>
            </form>
          ) : null}
          {nextSteps(item.incident.status).length > 0 ? (
            <div className="dispatch-actions">
              {nextSteps(item.incident.status).map((step) => (
                <Button
                  key={step.status}
                  type="button"
                  size="sm"
                  variant="outline"
                  disabled={update.isPending}
                  onClick={() => update.mutate({ id: item.incident.id, action: 'progress', body: { status: step.status } })}
                >
                  {step.label}
                </Button>
              ))}
            </div>
          ) : null}
          <Button
            type="button"
            size="sm"
            variant="outline"
            disabled={update.isPending}
            onClick={() => {
              if (window.confirm('Cancel this roadside request? Call the tow partner off first if a truck is on its way.')) {
                update.mutate({ id: item.incident.id, action: 'cancel' });
              }
            }}
          >
            Cancel request
          </Button>
          {item.incident.status === 'OPEN' ? (
            <>
              <Button
                type="button"
                variant="primary"
                size="sm"
                onClick={() => openAssign(item)}
              >
                Assign recovery
              </Button>
              {selected === item.incident.id && (
                <form
                  className="dispatch-form"
                  onSubmit={(event) => {
                    event.preventDefault();
                    assign.mutate({ id: item.incident.id, assignment });
                  }}
                >
                  <label>
                    Provider
                    <input
                      required
                      value={assignment.provider}
                      onChange={(event) =>
                        setAssignment({ ...assignment, provider: event.target.value })
                      }
                      placeholder="Cape Tow"
                    />
                  </label>
                  <label>
                    Quote reference
                    <input
                      required
                      value={assignment.quoteReference}
                      onChange={(event) =>
                        setAssignment({ ...assignment, quoteReference: event.target.value })
                      }
                      placeholder="quote-123"
                    />
                  </label>
                  <div className="dialog-row">
                    <label>
                      Amount (cents)
                      <input
                        required
                        min="0"
                        type="number"
                        value={assignment.amountMinor}
                        onChange={(event) =>
                          setAssignment({ ...assignment, amountMinor: event.target.value })
                        }
                      />
                    </label>
                    <label>
                      ETA (minutes)
                      <input
                        required
                        min="1"
                        max="1440"
                        type="number"
                        value={assignment.estimatedMinutes}
                        onChange={(event) =>
                          setAssignment({
                            ...assignment,
                            estimatedMinutes: event.target.value,
                          })
                        }
                      />
                    </label>
                  </div>
                  <label className="checkbox-label">
                    <input
                      type="checkbox"
                      checked={assignment.rideHome}
                      onChange={(event) =>
                        setAssignment({ ...assignment, rideHome: event.target.checked })
                      }
                    />
                    Arrange a safe ride home
                  </label>
                  <Button className="primary" type="submit" disabled={assign.isPending}>
                    {assign.isPending ? 'Assigning…' : 'Confirm assignment'}
                  </Button>
                </form>
              )}
            </>
          ) : null}
        </article>
        );
      })}
    </div>
  );
}
