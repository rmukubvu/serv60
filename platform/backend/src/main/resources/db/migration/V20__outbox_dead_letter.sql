-- Events that exhaust their publish attempts stop being retried and wait for an operator.
alter table outbox_event add column dead_lettered_at timestamptz;

-- The claim query must skip dead letters; rebuild the pending index with that predicate.
-- The table is small at pilot scale, so a plain (locking) index build is acceptable here.
drop index outbox_pending_lookup;
create index outbox_pending_lookup
  on outbox_event(available_at, occurred_at)
  where published_at is null and dead_lettered_at is null;
