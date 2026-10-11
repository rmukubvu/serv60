alter table visit_hold add column request_hash varchar(64);
create index visit_hold_idempotency_lookup on visit_hold(customer_id, idempotency_key);
