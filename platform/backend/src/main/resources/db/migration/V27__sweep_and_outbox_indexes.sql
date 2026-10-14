-- The hold expiry sweep and the per-customer held cap read only live holds.
create index visit_hold_held_expiry on visit_hold(expires_at) where status = 'HELD';

-- Ops payment alerts read two event types by recency.
create index outbox_payment_attention on outbox_event(occurred_at)
  where event_type in ('PaidVisitNeedsReconciliation', 'RefundRequired');

-- The dead-letter gauge and retention pruning.
create index outbox_dead_lettered on outbox_event(dead_lettered_at) where dead_lettered_at is not null;
create index outbox_published on outbox_event(published_at) where published_at is not null;
