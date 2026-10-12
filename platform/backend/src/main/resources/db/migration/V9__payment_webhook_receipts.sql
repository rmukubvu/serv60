create table payment_webhook_receipt (
  id uuid primary key,
  provider varchar(32) not null,
  event_id varchar(200) not null,
  payment_order_id uuid not null references payment_order(id),
  payload jsonb not null,
  received_at timestamptz not null,
  unique (provider, event_id)
);
create index payment_webhook_order_lookup on payment_webhook_receipt(payment_order_id, received_at);
