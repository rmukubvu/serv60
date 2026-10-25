'use client';

import { useQuery } from '@tanstack/react-query';
import { type ReactNode, useId, useRef, useState } from 'react';
import { apiFetch } from './api';
import { useHub } from './hub-context';
import { Button } from './ui/button';

export type CalendarEntry = {
  allocationId: string;
  holdId: string;
  resourceCode: string;
  serviceCode: string;
  startsAt: string;
  endsAt: string;
  status: 'HELD' | 'CONFIRMED';
};

type AppointmentDetails = {
  holdId: string;
  contact: {
    customerName: string;
    phoneNumber: string;
    vehicleMake?: string | null;
    vehicleModel?: string | null;
    registration?: string | null;
  } | null;
  complimentaryServiceCode: string | null;
};

function displayTime(value: string) {
  return new Intl.DateTimeFormat('en-ZA', { hour: '2-digit', minute: '2-digit' }).format(new Date(value));
}

async function fetchDetails(hubId: string, id: string, signal: AbortSignal): Promise<AppointmentDetails> {
  const response = await apiFetch(`/v1/hubs/${hubId}/appointments/${encodeURIComponent(id)}/details`, { signal, cache: 'no-store' });
  if (!response.ok) throw new Error(response.status === 403 ? 'Only hub staff can view these details.' : 'Unable to load appointment details. Please try again.');
  return response.json() as Promise<AppointmentDetails>;
}

function ContactDetails({ holdId }: { holdId: string }) {
  const { hubId } = useHub();
  const query = useQuery({ queryKey: ['appointment-details', hubId, holdId], queryFn: ({ signal }) => fetchDetails(hubId, holdId, signal), retry: false, gcTime: 0 });
  if (query.isPending) return <p role="status">Loading customer and car…</p>;
  if (query.isError) return <div role="alert"><p>{query.error.message}</p><Button type="button" onClick={() => query.refetch()}>Try again</Button></div>;
  const contact = query.data.contact;
  const vehicle = [contact?.vehicleMake, contact?.vehicleModel].filter(Boolean).join(' ');
  const phone = contact?.phoneNumber?.trim();
  const dialable = phone && /^\+?[\d\s()-]{7,32}$/.test(phone);
  return <>
    {!contact && <p className="details-note">Customer and car details haven&apos;t been provided for this booking.</p>}
    <dl className="booking-facts">
      <div><dt>Customer</dt><dd>{contact?.customerName || 'Not provided'}</dd></div>
      <div><dt>Phone</dt><dd>{dialable ? <a href={`tel:${phone.replace(/[^+\d]/g, '')}`}>{phone}</a> : phone || 'Not provided'}</dd></div>
      <div><dt>Vehicle</dt><dd>{vehicle || 'Not provided'}</dd></div>
      <div><dt>Registration</dt><dd>{contact?.registration || 'Not provided'}</dd></div>
      {query.data.complimentaryServiceCode && <div><dt>Complimentary</dt><dd>{query.data.complimentaryServiceCode === 'BARBER' ? 'Barber' : 'Private workspace'}</dd></div>}
    </dl>
  </>;
}

export function BookingCard({ job, actions }: { job: CalendarEntry; actions?: ReactNode }) {
  const [flipped, setFlipped] = useState(false);
  const summary = useRef<HTMLButtonElement>(null);
  const detailsId = useId();
  const service = job.serviceCode.replaceAll('_', ' ');
  return <div className={`booking-card ${job.status.toLowerCase()}`} data-flipped={flipped}>
    <div className="booking-flipper">
      <div className="booking-front" inert={flipped} aria-hidden={flipped}>
        <button ref={summary} type="button" className="booking-summary" aria-expanded={flipped} aria-controls={detailsId} aria-label={`View ${service} booking at ${displayTime(job.startsAt)}`} onClick={() => setFlipped(true)}>
          <time dateTime={job.startsAt}>{displayTime(job.startsAt)}</time>
          <span>
            <strong>{service}</strong>
            <span className="booking-status">
              <span className="booking-status-dot" aria-hidden="true" />
              {job.status === 'HELD' ? 'Held for customer' : 'Confirmed'}
            </span>
            <span className="booking-hint">View customer &amp; car ↗</span>
          </span>
        </button>
        {actions}
      </div>
      <section id={detailsId} className="booking-back" inert={!flipped} aria-hidden={!flipped} aria-label="Appointment details">
        {flipped && <>
          <div className="booking-detail-heading"><strong>Arriving at {displayTime(job.startsAt)}</strong><button type="button" ref={(node) => node?.focus()} className="booking-back-button" onClick={() => { setFlipped(false); requestAnimationFrame(() => summary.current?.focus()); }}>Back ↩</button></div>
          <p className="details-note">{service} · {job.status === 'HELD' ? 'Held' : 'Confirmed'}</p>
          <ContactDetails holdId={job.holdId} />
        </>}
      </section>
    </div>
  </div>;
}
