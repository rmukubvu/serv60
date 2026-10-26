-- Local demo only: a morning on the floor at the Cape Town pilot hub, so the Live floor board and
-- the app's "Track your visit" screen have cars to show the moment the stack starts. Re-runnable.
-- Apply: docker compose -f infra/docker-compose.yml exec -T postgres psql -U serv60 -d serv60 < infra/seed-demo-floor.sql
-- Fictional people and plates; never run against a shared database.

\set ON_ERROR_STOP on
\set hub '''00000000-0000-0000-0000-000000000060'''
\set customer '''00000000-0000-0000-0000-000000000001'''
\set template '''00000000-0000-0000-0000-000000000061'''

begin;

-- Wipe the previous run of this seed.
delete from visit_invoice where hold_id in (select id from visit_hold where idempotency_key like 'demo-floor-%');
delete from scope_change where hold_id in (select id from visit_hold where idempotency_key like 'demo-floor-%');
delete from visit_stage_event where hold_id in (select id from visit_hold where idempotency_key like 'demo-floor-%');
delete from resource_allocation where hold_id in (select id from visit_hold where idempotency_key like 'demo-floor-%');
delete from stock_reservation where hold_id in (select id from visit_hold where idempotency_key like 'demo-floor-%');
delete from visit_hold where idempotency_key like 'demo-floor-%';

-- The demo customer the app signs in as when security is off: a name, a phone and two cars.
insert into customer_profile(customer_id, customer_name, phone_number)
values (:customer::uuid, 'Alex Demo', '+27 82 000 0060')
on conflict (customer_id) do update set customer_name = excluded.customer_name, phone_number = excluded.phone_number;
-- Only one default per customer: keep an existing default if the account already has one.
insert into customer_vehicle(id, customer_id, vehicle_make, vehicle_model, registration, is_default)
values ('00000000-0000-0000-0000-000000000010', :customer::uuid, 'Toyota', 'Corolla Cross', 'CA 60 060',
        not exists (select 1 from customer_vehicle where customer_id = :customer::uuid and is_default))
on conflict (id) do nothing;
insert into customer_vehicle(id, customer_id, vehicle_make, vehicle_model, registration, is_default)
values ('00000000-0000-0000-0000-000000000011', :customer::uuid, 'Volkswagen', 'Polo Vivo', 'CA 61 061', false)
on conflict (id) do nothing;

-- Six visits around "now": one for the demo customer about to arrive, four other cars at
-- different points of the hour, and one handed over earlier. Times are relative so the seed
-- works whenever it is run; the hub's express bays are EXPRESS-01 to EXPRESS-04.
with plan(n, offset_minutes, owner, name, phone, make, model, plate, extra, bay) as (values
  (0, -3,  :customer::uuid,                                      'Alex Demo',           '+27 82 000 0060', 'Toyota',     'Corolla Cross', 'CA 60 060',  'BARBER',       '00000000-0000-0000-0000-000000000071'::uuid),
  (1, -26, 'd0000000-0000-0000-0000-000000000001'::uuid,         'Nadia Khan',          '+27 82 000 0001', 'Volkswagen', 'Polo',          'CA 123 456', 'PRIVATE_WORK', '00000000-0000-0000-0000-000000000072'::uuid),
  (2, -12, 'd0000000-0000-0000-0000-000000000002'::uuid,         'Sipho Dlamini',       '+27 82 000 0002', 'Toyota',     'Hilux',         'CA 234 567', null,           '00000000-0000-0000-0000-000000000073'::uuid),
  (3, 20,  'd0000000-0000-0000-0000-000000000003'::uuid,         'Lerato Mokoena',      '+27 82 000 0003', 'BMW',        '118i',          'CA 345 678', 'BARBER',       '00000000-0000-0000-0000-000000000074'::uuid),
  (4, 50,  'd0000000-0000-0000-0000-000000000004'::uuid,         'Johan van der Merwe', '+27 82 000 0004', 'Ford',       'Ranger',        'CA 456 789', null,           '00000000-0000-0000-0000-000000000071'::uuid),
  (5, -130,'d0000000-0000-0000-0000-000000000005'::uuid,         'Thandi Nkosi',        '+27 82 000 0005', 'Hyundai',    'i20',           'CA 567 890', 'PRIVATE_WORK', '00000000-0000-0000-0000-000000000072'::uuid)
),
holds as (
  insert into visit_hold (id, hub_id, customer_id, vehicle_id, service_template_id, starts_at, ends_at, status, policy,
                          expires_at, idempotency_key, appointment_contact, complimentary_service_code, confirmed_at, confirmed_by)
  select ('c0000000-0000-0000-0000-00000000000' || n)::uuid, :hub::uuid, owner,
         case when n = 0 then '00000000-0000-0000-0000-000000000010'::uuid end, :template::uuid,
         date_trunc('minute', now()) + (offset_minutes || ' minutes')::interval,
         date_trunc('minute', now()) + (offset_minutes || ' minutes')::interval + interval '1 hour',
         'CONFIRMED', 'READY_STOCK', now() + interval '1 day', 'demo-floor-' || n,
         jsonb_build_object('customerName', name, 'phoneNumber', phone, 'vehicleMake', make,
                            'vehicleModel', model, 'registration', plate),
         extra, now() - interval '1 day', 'demo-seed'
  from plan
  returning id, idempotency_key, starts_at, ends_at
)
insert into resource_allocation (id, hold_id, resource_id, starts_at, ends_at, status)
select ('c2000000-0000-0000-0000-00000000000' || plan.n)::uuid, holds.id, plan.bay, holds.starts_at, holds.ends_at, 'CONFIRMED'
from holds join plan on holds.idempotency_key = 'demo-floor-' || plan.n;

-- Where each car is right now. Visit 0 (the demo customer) is not checked in: the simulation, or
-- the Check in button, does that live. Visit 5 is done. Visit 2 is waiting on the customer.
insert into visit_stage_event (id, hold_id, stage, note, recorded_by, recorded_at)
select gen_random_uuid(), ('c0000000-0000-0000-0000-00000000000' || v.n)::uuid, s.stage, s.note, 'demo-seed',
       date_trunc('minute', now()) + (v.offset_minutes || ' minutes')::interval + (s.at_minutes || ' minutes')::interval
from (values (1, -26), (2, -12), (5, -130)) as v(n, offset_minutes)
join lateral (
  select * from (values
    (1, 'CHECKED_IN',    0,  'Keys in box 2'),
    (1, 'INSPECTING',    3,  null),
    (1, 'SERVICING',     9,  null),
    (1, 'QUALITY_CHECK', 21, null),
    (2, 'CHECKED_IN',    0,  null),
    (2, 'INSPECTING',    2,  'Front pads at 2 mm, see proposal'),
    (5, 'CHECKED_IN',    0,  null),
    (5, 'INSPECTING',    4,  null),
    (5, 'SERVICING',     9,  null),
    (5, 'QUALITY_CHECK', 41, null),
    (5, 'WASHING',       46, null),
    (5, 'READY',         55, null),
    (5, 'HANDED_OVER',   71, 'Collected by owner')
  ) as stages(n, stage, at_minutes, note)
  where stages.n = v.n
) s on true;

-- Extra work on Sipho's Hilux, waiting for an answer: the simulation holds that car at inspection.
insert into scope_change (id, hold_id, status, items, note, total_minor, currency, proposed_by, proposed_at)
values ('c3000000-0000-0000-0000-000000000002', 'c0000000-0000-0000-0000-000000000002', 'PROPOSED',
        '[{"description":"Front brake pads","amountMinor":145000},{"description":"Wiper blades","amountMinor":32000}]',
        'Pads at 2 mm, photos attached', 177000, 'ZAR', 'demo-seed', now() - interval '6 minutes');

commit;

select 'demo floor seeded: ' || count(*) || ' visits' from visit_hold where idempotency_key like 'demo-floor-%';
