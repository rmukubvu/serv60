'use client';

import { useMutation, useQueryClient } from '@tanstack/react-query';
import { type FormEvent, useState } from 'react';
import { Button } from './ui/button';

const HUB_ID = '00000000-0000-0000-0000-000000000060';
const API_BASE = process.env.NEXT_PUBLIC_API_BASE_URL ?? 'http://localhost:8080';
const DEMO_CUSTOMER = '00000000-0000-0000-0000-000000000001';

type CreateHold = { customerId: string; serviceCode: string; startsAt: string; endsAt: string; policy: 'READY_STOCK' | 'CONDITIONAL_PROCUREMENT' };

async function createAppointment(request: CreateHold) {
  const response = await fetch(`${API_BASE}/v1/visit-holds`, { method: 'POST', headers: { 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify({ hubId: HUB_ID, ...request }) });
  if (!response.ok) {
    const body = await response.json().catch(() => ({})) as { error?: string };
    throw new Error(body.error ?? `Booking request failed (${response.status})`);
  }
  return response.json();
}

export function AppointmentDialog() {
  const queryClient = useQueryClient();
  const [open, setOpen] = useState(false);
  const [serviceCode, setServiceCode] = useState('EXPRESS_OIL');
  const [customerId, setCustomerId] = useState(DEMO_CUSTOMER);
  const [startsAt, setStartsAt] = useState('');
  const [endsAt, setEndsAt] = useState('');
  const mutation = useMutation({ mutationFn: createAppointment, onSuccess: () => { queryClient.invalidateQueries({ queryKey: ['hub-calendar', HUB_ID] }); setOpen(false); } });

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    mutation.mutate({ customerId, serviceCode, startsAt: new Date(startsAt).toISOString(), endsAt: new Date(endsAt).toISOString(), policy: serviceCode === 'EXPRESS_OIL' ? 'READY_STOCK' : 'CONDITIONAL_PROCUREMENT' });
  }

  return <><Button className="primary" onClick={() => setOpen(true)}>Create appointment</Button>{open && <dialog open className="appointment-dialog" aria-labelledby="appointment-title"><form onSubmit={submit}><div className="dialog-heading"><div><span className="eyebrow">HUB 01</span><h2 id="appointment-title">Create appointment</h2></div><Button type="button" className="dialog-close" onClick={() => setOpen(false)} aria-label="Close">×</Button></div><label>Customer ID<input required value={customerId} onChange={(event) => setCustomerId(event.target.value)} /></label><label>Service<select value={serviceCode} onChange={(event) => setServiceCode(event.target.value)}><option value="EXPRESS_OIL">Express oil service</option><option value="BARBER">Barber</option><option value="PRIVATE_WORK">Private workspace</option></select></label><div className="dialog-row"><label>Starts<input required type="datetime-local" value={startsAt} onChange={(event) => setStartsAt(event.target.value)} /></label><label>Ends<input required type="datetime-local" value={endsAt} onChange={(event) => setEndsAt(event.target.value)} /></label></div>{mutation.isError && <p className="form-error" role="alert">{mutation.error.message}</p>}<div className="dialog-actions"><Button type="button" onClick={() => setOpen(false)}>Cancel</Button><Button className="primary" type="submit" disabled={mutation.isPending}>{mutation.isPending ? 'Creating…' : 'Create hold'}</Button></div></form></dialog>}</>;
}
