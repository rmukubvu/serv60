create table assistant_proposal (
  id uuid primary key,
  customer_id uuid not null,
  hub_id uuid not null references hub(id),
  kind varchar(16) not null check(kind in ('BOOK','CHANGE')),
  appointment_id uuid references visit_hold(id),
  payload jsonb not null,
  expires_at timestamptz not null,
  executed_hold_id uuid references visit_hold(id),
  created_at timestamptz not null default now()
);
create index assistant_proposal_expiry on assistant_proposal(expires_at);
create index assistant_proposal_hub on assistant_proposal(hub_id);
create index assistant_proposal_appointment on assistant_proposal(appointment_id);
create index assistant_proposal_executed on assistant_proposal(executed_hold_id);
create table assistant_usage (
  customer_id uuid not null,
  window_start timestamptz not null,
  requests integer not null,
  primary key(customer_id,window_start)
);
