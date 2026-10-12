alter table hub add column latitude double precision check (latitude between -90 and 90);
alter table hub add column longitude double precision check (longitude between -180 and 180);
alter table hub add column address varchar(240) not null default '';
alter table hub add column published boolean not null default false;
alter table hub add column opens_at time not null default '08:00';
alter table hub add column closes_at time not null default '17:00';
alter table hub add constraint hub_daily_hours check (closes_at > opens_at);
alter table hub add constraint published_hub_location check (not published or (latitude is not null and longitude is not null));
alter table visit_hold add column revision bigint not null default 0;
create index visit_hold_customer_upcoming on visit_hold(customer_id, starts_at, id) where status in ('HELD','CONFIRMED');
create table appointment_change_receipt (
  hold_id uuid not null references visit_hold(id),
  customer_id uuid not null,
  idempotency_key varchar(160) not null,
  request_hash varchar(64) not null,
  response jsonb not null,
  created_at timestamptz not null default now(),
  primary key (hold_id, customer_id, idempotency_key)
);
