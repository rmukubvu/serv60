create table hub (
  id uuid primary key,
  name varchar(160) not null,
  timezone varchar(80) not null,
  created_at timestamptz not null default now()
);
create table service_template (
  id uuid primary key,
  hub_id uuid not null references hub(id),
  code varchar(80) not null,
  name varchar(160) not null,
  duration_minutes integer not null check (duration_minutes > 0),
  required_parts jsonb not null default '[]',
  active boolean not null default true,
  unique (hub_id, code)
);
create table resource (
  id uuid primary key,
  hub_id uuid not null references hub(id),
  code varchar(80) not null,
  kind varchar(40) not null,
  active boolean not null default true,
  unique (hub_id, code)
);
create table visit_hold (
  id uuid primary key,
  hub_id uuid not null references hub(id),
  customer_id uuid not null,
  vehicle_id uuid,
  service_template_id uuid not null references service_template(id),
  starts_at timestamptz not null,
  ends_at timestamptz not null,
  status varchar(24) not null,
  policy varchar(32) not null,
  expires_at timestamptz not null,
  idempotency_key varchar(160) not null,
  created_at timestamptz not null default now(),
  unique (customer_id, idempotency_key),
  check (ends_at > starts_at)
);
create table resource_allocation (
  id uuid primary key,
  hold_id uuid not null references visit_hold(id),
  resource_id uuid not null references resource(id),
  starts_at timestamptz not null,
  ends_at timestamptz not null,
  status varchar(24) not null,
  check (ends_at > starts_at)
);
create index resource_allocation_lookup on resource_allocation(resource_id, starts_at, ends_at);
create table stock_balance (
  hub_id uuid not null references hub(id),
  sku varchar(120) not null,
  usable_quantity integer not null check (usable_quantity >= 0),
  reserved_quantity integer not null default 0 check (reserved_quantity >= 0),
  primary key (hub_id, sku)
);
create table stock_reservation (
  id uuid primary key,
  hold_id uuid not null references visit_hold(id),
  hub_id uuid not null references hub(id),
  sku varchar(120) not null,
  quantity integer not null check (quantity > 0),
  status varchar(24) not null
);
create table parts_demand (
  id uuid primary key,
  hold_id uuid not null references visit_hold(id),
  hub_id uuid not null references hub(id),
  sku varchar(120) not null,
  quantity integer not null check (quantity > 0),
  status varchar(24) not null,
  created_at timestamptz not null default now()
);
create index parts_demand_lookup on parts_demand(hub_id, status, created_at);
create table outbox_event (
  id uuid primary key,
  event_type varchar(160) not null,
  aggregate_id uuid not null,
  payload jsonb not null,
  occurred_at timestamptz not null default now(),
  published_at timestamptz
);
insert into hub(id, name, timezone) values ('00000000-0000-0000-0000-000000000060', 'Serv60 Cape Town Pilot', 'Africa/Johannesburg');
insert into resource(id, hub_id, code, kind) values
('00000000-0000-0000-0000-000000000071','00000000-0000-0000-0000-000000000060','EXPRESS-01','EXPRESS_BAY'),
('00000000-0000-0000-0000-000000000072','00000000-0000-0000-0000-000000000060','EXPRESS-02','EXPRESS_BAY'),
('00000000-0000-0000-0000-000000000073','00000000-0000-0000-0000-000000000060','EXPRESS-03','EXPRESS_BAY'),
('00000000-0000-0000-0000-000000000074','00000000-0000-0000-0000-000000000060','EXPRESS-04','EXPRESS_BAY'),
('00000000-0000-0000-0000-000000000075','00000000-0000-0000-0000-000000000060','BARBER-01','BARBER_CHAIR'),
('00000000-0000-0000-0000-000000000076','00000000-0000-0000-0000-000000000060','WORK-01','WORKSPACE');
insert into stock_balance(hub_id, sku, usable_quantity, reserved_quantity) values
('00000000-0000-0000-0000-000000000060','OIL-5W30',40,0),
('00000000-0000-0000-0000-000000000060','FILTER-OIL-001',40,0);
insert into service_template(id, hub_id, code, name, duration_minutes, required_parts) values
('00000000-0000-0000-0000-000000000061','00000000-0000-0000-0000-000000000060','EXPRESS_OIL','Express oil service',60,'[{"sku":"OIL-5W30","quantity":1},{"sku":"FILTER-OIL-001","quantity":1}]'),
('00000000-0000-0000-0000-000000000062','00000000-0000-0000-0000-000000000060','BARBER','Barber appointment',45,'[]'),
('00000000-0000-0000-0000-000000000063','00000000-0000-0000-0000-000000000060','PRIVATE_WORK','Private workspace',60,'[]');
