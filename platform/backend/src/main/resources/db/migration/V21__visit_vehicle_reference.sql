-- Profile saves now keep vehicle ids, so a booking can reference its vehicle reliably. Deleting a
-- vehicle detaches it from bookings (their contact snapshot keeps the car details).
-- NOT VALID: enforced for new and updated rows only; legacy holds may reference unknown ids.
alter table visit_hold
  add constraint visit_hold_vehicle_fk foreign key (vehicle_id)
  references customer_vehicle(id) on delete set null not valid;
create index visit_hold_vehicle_lookup on visit_hold(vehicle_id) where vehicle_id is not null;
