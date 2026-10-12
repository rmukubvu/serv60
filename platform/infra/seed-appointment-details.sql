-- Local demo only. Fictional contact information; never run on production data.
update visit_hold
set appointment_contact = '{"customerName":"Alex Demo","phoneNumber":"+27 00 000 0000","vehicleMake":"Toyota","vehicleModel":"Corolla","registration":"DEMO 060"}'::jsonb
where hub_id = '00000000-0000-0000-0000-000000000060'
  and customer_id = '00000000-0000-0000-0000-000000000001'
  and appointment_contact is null;
