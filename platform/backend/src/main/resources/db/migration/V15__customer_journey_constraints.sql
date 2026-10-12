-- Bounded cross-customer retention must not scan all active quota windows on each turn.
create index assistant_usage_retention on assistant_usage(window_start);

-- Expand the existing complimentary option invariant for hubs offering massage.
alter table visit_hold drop constraint visit_hold_complimentary_service_code_check;
alter table visit_hold add constraint visit_hold_complimentary_service_code_check
  check (complimentary_service_code is null or complimentary_service_code in ('BARBER','MASSAGE','PRIVATE_WORK'));
