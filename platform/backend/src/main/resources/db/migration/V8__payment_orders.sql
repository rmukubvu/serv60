create table payment_order (
  id uuid primary key,
  customer_id uuid not null,
  hold_id uuid not null references visit_hold(id),
  amount_minor bigint not null check (amount_minor > 0),
  currency varchar(3) not null,
  provider varchar(32) not null,
  provider_reference varchar(160),
  status varchar(24) not null,
  idempotency_key varchar(160) not null,
  request_hash varchar(64) not null,
  created_at timestamptz not null,
  updated_at timestamptz not null,
  unique (customer_id, idempotency_key),
  unique (provider, provider_reference)
);
create index payment_order_hold_lookup on payment_order(hold_id, status);
