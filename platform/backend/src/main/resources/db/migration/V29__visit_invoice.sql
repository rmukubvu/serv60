-- The invoice a visit receives when the car is handed over: the service, the complimentary option
-- and any extra work the customer approved, each marked paid in the app or due at the counter.
-- One per visit; lines are a snapshot so later price changes never alter an issued invoice.
create table visit_invoice (
  id uuid primary key,
  hold_id uuid not null unique references visit_hold(id),
  customer_id uuid not null,
  hub_id uuid not null references hub(id),
  number varchar(32) not null unique,
  lines jsonb not null check (jsonb_typeof(lines) = 'array'),
  total_minor bigint not null check (total_minor >= 0),
  paid_minor bigint not null check (paid_minor >= 0),
  due_minor bigint not null check (due_minor >= 0),
  currency varchar(3) not null default 'ZAR',
  issued_at timestamptz not null
);
create index visit_invoice_customer_history on visit_invoice(customer_id, issued_at desc);
