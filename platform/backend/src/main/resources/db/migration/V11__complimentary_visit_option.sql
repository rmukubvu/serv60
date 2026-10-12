alter table visit_hold add column complimentary_service_code varchar(80)
  check (complimentary_service_code in ('BARBER', 'PRIVATE_WORK'));
