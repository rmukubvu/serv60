alter table outbox_event
  add column attempt_count integer not null default 0,
  add column available_at timestamptz not null default now(),
  add column lease_until timestamptz,
  add column last_error varchar(1000);

create index outbox_pending_lookup
  on outbox_event(available_at, occurred_at)
  where published_at is null;
