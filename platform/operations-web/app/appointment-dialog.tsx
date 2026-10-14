'use client';

import { useMutation, useQueryClient } from '@tanstack/react-query';
import { type FormEvent, useEffect, useRef, useState } from 'react';
import { API_BASE, DEMO_CUSTOMER_ID } from './config';
import { useHub } from './hub-context';
import { Button } from './ui/button';


type AppointmentContact = { customerName: string; phoneNumber: string; vehicleMake: string; vehicleModel: string; registration: string };
type CreateHold = { hubId: string; contact: AppointmentContact; customerId: string; serviceCode: string; startsAt: string; endsAt: string; policy: 'READY_STOCK' | 'CONDITIONAL_PROCUREMENT' };

async function createAppointment(request: CreateHold) {
  const response = await fetch(`${API_BASE}/v1/visit-holds`, { method: 'POST', headers: { 'Content-Type': 'application/json', 'Idempotency-Key': crypto.randomUUID() }, body: JSON.stringify(request) });
  if (!response.ok) {
    const body = await response.json().catch(() => ({})) as { error?: string };
    throw new Error(body.error ?? `Booking request failed (${response.status})`);
  }
  return response.json();
}

export function AppointmentDialog() {
  const { hubId } = useHub();
  const queryClient = useQueryClient();
  const [contact, setContact] = useState<AppointmentContact>({ customerName: '', phoneNumber: '', vehicleMake: '', vehicleModel: '', registration: '' });
  const [open, setOpen] = useState(false);
  const dialogRef = useRef<HTMLDialogElement>(null);
  useEffect(() => {
    const dialog = dialogRef.current;
    if (!dialog) return;
    if (open && !dialog.open) dialog.showModal();
    else if (!open && dialog.open) dialog.close();
  }, [open]);
  const [serviceCode, setServiceCode] = useState('EXPRESS_OIL');
  const [customerId, setCustomerId] = useState(DEMO_CUSTOMER_ID);
  const [startsAt, setStartsAt] = useState('');
  const [endsAt, setEndsAt] = useState('');
  const mutation = useMutation({ mutationFn: createAppointment, onSuccess: () => { queryClient.invalidateQueries({ queryKey: ['hub-calendar', hubId] }); setOpen(false); } });

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const walkInId = customerId.trim() || crypto.randomUUID();
    mutation.mutate({ hubId, contact, customerId: walkInId, serviceCode, startsAt: new Date(startsAt).toISOString(), endsAt: new Date(endsAt).toISOString(), policy: serviceCode === 'EXPRESS_OIL' ? 'READY_STOCK' : 'CONDITIONAL_PROCUREMENT' });
  }

  return <><Button className="primary" onClick={() => setOpen(true)}>Create appointment</Button><dialog ref={dialogRef} onClose={() => setOpen(false)} className="appointment-dialog" aria-labelledby="appointment-title"><form onSubmit={submit}><div className="dialog-heading"><div><span className="eyebrow">WALK-IN OR BOOKED</span><h2 id="appointment-title">Create appointment</h2></div><Button type="button" className="dialog-close" onClick={() => setOpen(false)} aria-label="Close">×</Button></div><label>Customer ID<input value={customerId} onChange={(event) => setCustomerId(event.target.value)} placeholder="Leave blank to create a walk-in customer" /></label><fieldset className="contact-fields"><legend>Customer &amp; car</legend>{([
    ['customerName', 'Customer name', 160], ['phoneNumber', 'Phone number', 32], ['vehicleMake', 'Vehicle make', 80], ['vehicleModel', 'Vehicle model', 80], ['registration', 'Registration', 24]
  ] as const).map(([field, label, maxLength]) => <label key={field}>{label}<input required maxLength={maxLength} type={field === 'phoneNumber' ? 'tel' : 'text'} value={contact[field]} onChange={(event) => setContact({ ...contact, [field]: event.target.value })} /></label>)}</fieldset><label>Service<select value={serviceCode} onChange={(event) => setServiceCode(event.target.value)}><option value="EXPRESS_OIL">Express oil service</option><option value="BARBER">Barber</option><option value="MASSAGE">Massage</option><option value="PRIVATE_WORK">Private workspace</option></select></label><div className="dialog-row"><label>Starts<input required type="datetime-local" value={startsAt} onChange={(event) => setStartsAt(event.target.value)} /></label><label>Ends<input required type="datetime-local" value={endsAt} onChange={(event) => setEndsAt(event.target.value)} /></label></div>{mutation.isError && <p className="form-error" role="alert">{mutation.error.message}</p>}<div className="dialog-actions"><Button type="button" onClick={() => setOpen(false)}>Cancel</Button><Button className="primary" type="submit" disabled={mutation.isPending}>{mutation.isPending ? 'Creating…' : 'Create hold'}</Button></div></form></dialog></>;
}
