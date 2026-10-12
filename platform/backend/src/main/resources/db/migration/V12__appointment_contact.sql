-- Optional booking snapshot: old bookings remain readable without contact information.
alter table visit_hold add column appointment_contact jsonb
  check (appointment_contact is null or jsonb_typeof(appointment_contact) = 'object');
