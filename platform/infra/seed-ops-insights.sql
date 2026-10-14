-- Local demo only. Seeds floor insights + ranked roadside dispatch for the Cape Town pilot hub.
-- Apply: docker compose -f infra/docker-compose.yml exec -T postgres psql -U serv60 -d serv60 < infra/seed-ops-insights.sql

\set ON_ERROR_STOP on

-- Stable demo ids (re-runnable).
\set hub '''00000000-0000-0000-0000-000000000060'''
\set customer '''00000000-0000-0000-0000-000000000001'''
\set vehicle '''00000000-0000-0000-0000-000000000010'''
\set template '''00000000-0000-0000-0000-000000000061'''

-- Wipe prior insight demo rows.
delete from parts_demand
 where id in (
   'b1000000-0000-0000-0000-000000000001',
   'b1000000-0000-0000-0000-000000000002'
 );
delete from resource_allocation
 where hold_id in (
   select id from visit_hold where idempotency_key like 'ops-insight-%'
 );
delete from stock_reservation
 where hold_id in (
   select id from visit_hold where idempotency_key like 'ops-insight-%'
 );
delete from visit_hold where idempotency_key like 'ops-insight-%';
delete from roadside_incident where idempotency_key like 'ops-insight-%';

-- Parts pressure: thin stock + elevated pending demand.
update stock_balance
   set usable_quantity = 4, reserved_quantity = 2
 where hub_id = :hub::uuid and sku = 'FILTER-OIL-001';
update stock_balance
   set usable_quantity = 6, reserved_quantity = 3
 where hub_id = :hub::uuid and sku = 'OIL-5W30';

-- Shared slot ~4h ahead (same hour → floor pressure above 4 express bays).
with slot as (
  select date_trunc('hour', now() + interval '4 hours') as starts
),
holds as (
  insert into visit_hold (
    id, hub_id, customer_id, vehicle_id, service_template_id,
    starts_at, ends_at, status, policy, expires_at, idempotency_key, appointment_contact
  )
  select
    ('b0000000-0000-0000-0000-00000000000' || n)::uuid,
    :hub::uuid,
    :customer::uuid,
    :vehicle::uuid,
    :template::uuid,
    slot.starts,
    slot.starts + interval '1 hour',
    case when n = 5 then 'HELD' else 'CONFIRMED' end,
    'READY_STOCK',
    now() + interval '2 hours',
    'ops-insight-bay-' || n,
    jsonb_build_object(
      'customerName', 'Insight Demo ' || n,
      'phoneNumber', '+27 00 000 000' || n,
      'vehicleMake', 'Toyota',
      'vehicleModel', 'Corolla',
      'registration', 'INS ' || n
    )
  from slot, generate_series(1, 5) as n
  returning id, starts_at, ends_at, status
)
insert into resource_allocation (id, hold_id, resource_id, starts_at, ends_at, status)
select
  ('b2000000-0000-0000-0000-00000000000' || row_number() over ())::uuid,
  holds.id,
  case row_number() over ()
    when 1 then '00000000-0000-0000-0000-000000000071'::uuid
    when 2 then '00000000-0000-0000-0000-000000000072'::uuid
    when 3 then '00000000-0000-0000-0000-000000000073'::uuid
    when 4 then '00000000-0000-0000-0000-000000000074'::uuid
    else '00000000-0000-0000-0000-000000000071'::uuid
  end,
  holds.starts_at,
  holds.ends_at,
  holds.status
from holds;

-- Held visit starting soon (HELD_SOON alert).
insert into visit_hold (
  id, hub_id, customer_id, vehicle_id, service_template_id,
  starts_at, ends_at, status, policy, expires_at, idempotency_key, appointment_contact
) values (
  'b0000000-0000-0000-0000-000000000010',
  :hub::uuid,
  :customer::uuid,
  :vehicle::uuid,
  :template::uuid,
  now() + interval '45 minutes',
  now() + interval '105 minutes',
  'HELD',
  'READY_STOCK',
  now() + interval '30 minutes',
  'ops-insight-held-soon',
  '{"customerName":"Held Soon Demo","phoneNumber":"+27 00 000 0010","vehicleMake":"VW","vehicleModel":"Polo","registration":"HELD 10"}'::jsonb
);

insert into resource_allocation (id, hold_id, resource_id, starts_at, ends_at, status)
values (
  'b2000000-0000-0000-0000-000000000010',
  'b0000000-0000-0000-0000-000000000010',
  '00000000-0000-0000-0000-000000000072',
  now() + interval '45 minutes',
  now() + interval '105 minutes',
  'HELD'
);

-- Pending parts demand against the demo holds (shortage + tight buffer).
insert into parts_demand (id, hold_id, hub_id, sku, quantity, status) values
  ('b1000000-0000-0000-0000-000000000001', 'b0000000-0000-0000-0000-000000000001', :hub::uuid, 'FILTER-OIL-001', 4, 'PENDING'),
  ('b1000000-0000-0000-0000-000000000002', 'b0000000-0000-0000-0000-000000000002', :hub::uuid, 'OIL-5W30', 2, 'PENDING');

-- Ranked roadside queue: older OPEN + ride-home beats a fresher incident.
insert into roadside_incident (
  id, customer_id, vehicle_id, location, description, status, idempotency_key, request_hash,
  latitude, longitude, ride_home_requested, created_at
) values
(
  'c0000000-0000-0000-0000-000000000001',
  :customer::uuid,
  :vehicle::uuid,
  'Sea Point Main Rd',
  'Battery flat outside Checkers — customer requests tow and a ride home.',
  'OPEN',
  'ops-insight-roadside-1',
  'ops-insight-1',
  -33.9115,
  18.3875,
  true,
  now() - interval '35 minutes'
),
(
  'c0000000-0000-0000-0000-000000000002',
  :customer::uuid,
  :vehicle::uuid,
  'Buitengracht Street',
  'Puncture in City Bowl — waiting for recovery.',
  'SEARCHING',
  'ops-insight-roadside-2',
  'ops-insight-2',
  -33.9240,
  18.4170,
  false,
  now() - interval '12 minutes'
),
(
  'c0000000-0000-0000-0000-000000000003',
  :customer::uuid,
  :vehicle::uuid,
  'Claremont Main',
  'Overheating near Cavendish — quoted, awaiting assign.',
  'QUOTED',
  'ops-insight-roadside-3',
  'ops-insight-3',
  -33.9810,
  18.4640,
  false,
  now() - interval '8 minutes'
);

select 'ops insights seed applied' as status;
