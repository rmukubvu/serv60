create table roadside_incident (
  id uuid primary key,
  customer_id uuid not null,
  vehicle_id uuid,
  location varchar(500) not null,
  description varchar(2000) not null,
  status varchar(32) not null,
  idempotency_key varchar(160) not null,
  created_at timestamptz not null default now(),
  unique (customer_id, idempotency_key)
);
create index roadside_incident_customer_lookup on roadside_incident(customer_id, created_at desc);
