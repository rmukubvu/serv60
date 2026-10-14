-- Who confirmed a visit and when: an operator (possibly without payment), a settled payment, or
-- a customer re-check. Additive and nullable so previous application versions keep working.
alter table visit_hold
  add column confirmed_at timestamptz,
  add column confirmed_by varchar(200);
