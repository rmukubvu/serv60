alter table hub add column settings_revision bigint not null default 0 check (settings_revision >= 0);
create table hub_trading_day (
  hub_id uuid not null references hub(id),
  day_of_week smallint not null check (day_of_week between 1 and 7),
  closed boolean not null,
  opens_at time not null,
  closes_at time not null,
  primary key (hub_id, day_of_week),
  check (closed or closes_at - opens_at >= interval '1 hour')
);
insert into hub_trading_day(hub_id,day_of_week,closed,opens_at,closes_at)
select id,day,false,opens_at,closes_at from hub cross join generate_series(1,7) day;
