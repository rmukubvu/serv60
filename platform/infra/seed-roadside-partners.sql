-- Illustrative Cape Town tow fleet for local demos only (City Bowl / Sea Point / Claremont).
-- These are not real companies. Never run this against a shared or production database.
insert into roadside_partner(id, name, latitude, longitude, online, callout_minor, per_km_minor, capacity) values
  ('a0000000-0000-0000-0000-000000000001', 'City Bowl Tow', -33.9245, 18.4180, true, 45000, 1800, 2),
  ('a0000000-0000-0000-0000-000000000002', 'Sea Point Recovery', -33.9120, 18.3880, true, 48000, 1900, 1),
  ('a0000000-0000-0000-0000-000000000003', 'Southern Suburbs Tow', -33.9800, 18.4650, true, 42000, 1700, 2),
  ('a0000000-0000-0000-0000-000000000004', 'Atlantic Assist', -33.9050, 18.3950, true, 50000, 2000, 1),
  ('a0000000-0000-0000-0000-000000000005', 'Peninsula Heavy', -33.9550, 18.4800, true, 55000, 2200, 1)
on conflict (id) do update set online = true;
