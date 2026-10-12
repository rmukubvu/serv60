create table roadside_assignment (
  id uuid primary key,
  incident_id uuid not null references roadside_incident(id),
  provider varchar(120) not null,
  quote_reference varchar(160) not null,
  amount_minor bigint not null,
  currency varchar(3) not null,
  estimated_minutes integer not null,
  ride_home boolean not null default false,
  status varchar(32) not null,
  created_at timestamptz not null default now(),
  unique (incident_id)
);
create index roadside_assignment_status_lookup on roadside_assignment(status, created_at);
