create table customer_profile (
  customer_id uuid primary key,
  customer_name varchar(160) not null default '',
  phone_number varchar(32) not null default '',
  updated_at timestamptz not null default now()
);

create table customer_vehicle (
  id uuid primary key,
  customer_id uuid not null references customer_profile(customer_id) on delete cascade,
  vehicle_make varchar(80) not null,
  vehicle_model varchar(80) not null,
  registration varchar(24) not null,
  is_default boolean not null default false,
  created_at timestamptz not null default now()
);

create unique index customer_vehicle_one_default
  on customer_vehicle(customer_id) where is_default;
create index customer_vehicle_owner on customer_vehicle(customer_id);
