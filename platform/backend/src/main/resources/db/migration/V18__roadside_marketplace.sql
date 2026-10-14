alter table roadside_incident
  add column latitude double precision,
  add column longitude double precision,
  add column destination_label varchar(240),
  add column destination_latitude double precision,
  add column destination_longitude double precision,
  add column ride_home_requested boolean not null default false,
  add column selected_quote_id uuid,
  add column dispatch_generation int not null default 0,
  add column truck_label varchar(120),
  add column truck_latitude double precision,
  add column truck_longitude double precision,
  add column eta_minutes integer;

create table roadside_partner (
  id uuid primary key,
  name varchar(120) not null,
  latitude double precision not null,
  longitude double precision not null,
  online boolean not null default true,
  callout_minor bigint not null check (callout_minor >= 0),
  per_km_minor bigint not null check (per_km_minor >= 0),
  capacity int not null default 1 check (capacity between 0 and 20),
  updated_at timestamptz not null default now()
);

create table roadside_quote (
  id uuid primary key,
  incident_id uuid not null references roadside_incident(id),
  partner_id uuid references roadside_partner(id),
  partner_name varchar(120) not null,
  amount_minor bigint not null,
  ride_amount_minor bigint not null default 0,
  currency varchar(3) not null default 'ZAR',
  eta_minutes int not null,
  distance_km double precision not null,
  expires_at timestamptz not null,
  created_at timestamptz not null default now()
);

create table roadside_dispatch_offer (
  id uuid primary key,
  incident_id uuid not null references roadside_incident(id),
  partner_id uuid not null references roadside_partner(id),
  generation int not null,
  status varchar(24) not null,
  expires_at timestamptz not null,
  created_at timestamptz not null default now()
);
create index roadside_offer_open on roadside_dispatch_offer(incident_id, status);

create table roadside_ride (
  id uuid primary key,
  incident_id uuid not null references roadside_incident(id),
  provider varchar(40) not null,
  status varchar(32) not null,
  amount_minor bigint not null,
  currency varchar(3) not null default 'ZAR',
  dropoff_label varchar(240) not null,
  dropoff_latitude double precision,
  dropoff_longitude double precision,
  tracking_url varchar(500),
  pin_code varchar(12),
  created_at timestamptz not null default now(),
  unique (incident_id)
);

create table roadside_payment (
  id uuid primary key,
  incident_id uuid not null references roadside_incident(id),
  customer_id uuid not null,
  amount_minor bigint not null,
  currency varchar(3) not null default 'ZAR',
  status varchar(24) not null,
  created_at timestamptz not null default now(),
  unique (incident_id)
);

-- Demo Cape Town fleet (illustrative positions near City Bowl / Sea Point / Claremont).
insert into roadside_partner(id, name, latitude, longitude, online, callout_minor, per_km_minor, capacity) values
  ('a0000000-0000-0000-0000-000000000001', 'City Bowl Tow', -33.9245, 18.4180, true, 45000, 1800, 2),
  ('a0000000-0000-0000-0000-000000000002', 'Sea Point Recovery', -33.9120, 18.3880, true, 48000, 1900, 1),
  ('a0000000-0000-0000-0000-000000000003', 'Southern Suburbs Tow', -33.9800, 18.4650, true, 42000, 1700, 2),
  ('a0000000-0000-0000-0000-000000000004', 'Atlantic Assist', -33.9050, 18.3950, true, 50000, 2000, 1),
  ('a0000000-0000-0000-0000-000000000005', 'Peninsula Heavy', -33.9550, 18.4800, true, 55000, 2200, 1);
