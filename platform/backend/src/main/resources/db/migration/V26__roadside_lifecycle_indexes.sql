-- Roadside requests now close (COMPLETED, EXPIRED). The expiry sweep looks only at QUOTED rows,
-- and the board and per-customer cap look only at open ones, so both stay small as history grows.
create index roadside_incident_quoted on roadside_incident(created_at) where status = 'QUOTED';
create index roadside_incident_customer_open on roadside_incident(customer_id)
  where status not in ('COMPLETED', 'CANCELLED', 'EXPIRED');
