create index visit_hold_hub_time_lookup
  on visit_hold(hub_id, starts_at, ends_at)
  where status in ('HELD', 'CONFIRMED');

create index resource_allocation_active_time_lookup
  on resource_allocation(starts_at, ends_at, resource_id)
  where status in ('HELD', 'CONFIRMED');
