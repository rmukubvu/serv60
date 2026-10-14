-- A hold is extended once when the customer starts paying, so 3-D Secure cannot lose the slot;
-- recording when stops repeated payment calls from extending it forever.
alter table visit_hold add column checkout_extended_at timestamptz;

-- Partial refunds accumulate here; the order becomes REFUNDED only when the total is refunded.
alter table payment_order add column refunded_minor bigint not null default 0
  check (refunded_minor >= 0);
