'use client';

import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useState } from 'react';

import { API_BASE } from './config';
import { Button } from './ui/button';

type Incident = { id: string; status: 'OPEN' | 'ASSIGNED' | 'CANCELLED'; location: string; description: string; createdAt: string };
type Assignment = { provider: string; quoteReference: string; amountMinor: string; currency: string; estimatedMinutes: string; rideHome: boolean };
const emptyAssignment: Assignment = { provider: '', quoteReference: '', amountMinor: '', currency: 'ZAR', estimatedMinutes: '', rideHome: false };

async function fetchDispatchQueue(): Promise<Incident[]> {
  const response = await fetch(`${API_BASE}/v1/roadside/incidents/dispatch?limit=50`);
  if (!response.ok) throw new Error(`Dispatch queue request failed (${response.status})`);
  return response.json() as Promise<Incident[]>;
}

async function assignIncident({ id, assignment }: { id: string; assignment: Assignment }) {
  const response = await fetch(`${API_BASE}/v1/roadside/incidents/${id}/assignment`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ ...assignment, amountMinor: Number(assignment.amountMinor), estimatedMinutes: Number(assignment.estimatedMinutes) }) });
  if (!response.ok) {
    const body = await response.json().catch(() => ({})) as { error?: string };
    throw new Error(body.error ?? `Assignment failed (${response.status})`);
  }
  return response.json();
}

function ageLabel(createdAt: string) {
  const minutes = Math.max(0, Math.round((Date.now() - new Date(createdAt).getTime()) / 60000));
  return minutes === 0 ? 'Just now' : `${minutes} min waiting`;
}

export function RoadsideDispatch() {
  const queryClient = useQueryClient();
  const [selected, setSelected] = useState<string | null>(null);
  const [assignment, setAssignment] = useState<Assignment>(emptyAssignment);
  const queue = useQuery({ queryKey: ['roadside-dispatch'], queryFn: fetchDispatchQueue, refetchInterval: 30_000 });
  const assign = useMutation({ mutationFn: assignIncident, onSuccess: () => { setSelected(null); setAssignment(emptyAssignment); queryClient.invalidateQueries({ queryKey: ['roadside-dispatch'] }); } });

  if (queue.isLoading) return <div className="calendar-state" role="status">Loading dispatch queue…</div>;
  if (queue.isError) return <div className="calendar-state error" role="alert">Unable to load roadside dispatch queue.</div>;
  if ((queue.data ?? []).length === 0) return <div className="calendar-state">No roadside incidents are waiting for dispatch.</div>;

  return <div className="dispatch-list">
    {assign.isError && <p className="form-error" role="alert">{assign.error.message}</p>}
    {(queue.data ?? []).map((incident) => <article className="dispatch-card" key={incident.id}>
      <div className="dispatch-card-heading"><div><strong>{incident.location}</strong><span>{ageLabel(incident.createdAt)} · {incident.id.slice(0, 8)}</span></div><b>OPEN</b></div>
      <p>{incident.description}</p>
      <Button type="button" className="primary" onClick={() => setSelected(selected === incident.id ? null : incident.id)}>Assign recovery</Button>
      {selected === incident.id && <form className="dispatch-form" onSubmit={(event) => { event.preventDefault(); assign.mutate({ id: incident.id, assignment }); }}>
        <label>Provider<input required value={assignment.provider} onChange={(event) => setAssignment({ ...assignment, provider: event.target.value })} placeholder="Cape Tow" /></label>
        <label>Quote reference<input required value={assignment.quoteReference} onChange={(event) => setAssignment({ ...assignment, quoteReference: event.target.value })} placeholder="quote-123" /></label>
        <div className="dialog-row"><label>Amount (cents)<input required min="0" type="number" value={assignment.amountMinor} onChange={(event) => setAssignment({ ...assignment, amountMinor: event.target.value })} /></label><label>ETA (minutes)<input required min="1" max="1440" type="number" value={assignment.estimatedMinutes} onChange={(event) => setAssignment({ ...assignment, estimatedMinutes: event.target.value })} /></label></div>
        <label className="checkbox-label"><input type="checkbox" checked={assignment.rideHome} onChange={(event) => setAssignment({ ...assignment, rideHome: event.target.checked })} /> Arrange a safe ride home</label>
        <Button className="primary" type="submit" disabled={assign.isPending}>{assign.isPending ? 'Assigning…' : 'Confirm assignment'}</Button>
      </form>}
    </article>)}
  </div>;
}
