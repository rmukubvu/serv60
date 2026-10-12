-- Development only: illustrative hub locations, not operating business addresses.
begin;
update hub set name='Serv60 City Bowl · Demo',address='Cape Town City Bowl · illustrative location',
 latitude=-33.925,longitude=18.424,published=true where id='00000000-0000-0000-0000-000000000060';
insert into hub(id,name,timezone,address,latitude,longitude,published) values
 ('00000000-0000-0000-0000-000000000160','Serv60 Sea Point · Demo','Africa/Johannesburg','Sea Point · illustrative location',-33.918,18.389,true),
 ('00000000-0000-0000-0000-000000000260','Serv60 Claremont · Demo','Africa/Johannesburg','Claremont · illustrative location',-33.982,18.466,true)
on conflict(id) do nothing;
insert into resource(id,hub_id,code,kind) values
 ('00000000-0000-0000-0000-000000000171','00000000-0000-0000-0000-000000000160','EXPRESS-01','EXPRESS_BAY'),
 ('00000000-0000-0000-0000-000000000172','00000000-0000-0000-0000-000000000160','BARBER-01','BARBER_CHAIR'),
 ('00000000-0000-0000-0000-000000000173','00000000-0000-0000-0000-000000000160','MASSAGE-01','MASSAGE_ROOM'),
 ('00000000-0000-0000-0000-000000000271','00000000-0000-0000-0000-000000000260','EXPRESS-01','EXPRESS_BAY'),
 ('00000000-0000-0000-0000-000000000272','00000000-0000-0000-0000-000000000260','WORK-01','WORKSPACE')
on conflict(id) do nothing;
insert into service_template(id,hub_id,code,name,duration_minutes,required_parts) values
 ('00000000-0000-0000-0000-000000000161','00000000-0000-0000-0000-000000000160','EXPRESS_OIL','Express vehicle service',60,'[{"sku":"OIL-5W30","quantity":1},{"sku":"FILTER-OIL-001","quantity":1}]'),
 ('00000000-0000-0000-0000-000000000162','00000000-0000-0000-0000-000000000160','BARBER','Barber',45,'[]'),
 ('00000000-0000-0000-0000-000000000163','00000000-0000-0000-0000-000000000160','MASSAGE','Massage',45,'[]'),
 ('00000000-0000-0000-0000-000000000261','00000000-0000-0000-0000-000000000260','EXPRESS_OIL','Express vehicle service',60,'[{"sku":"OIL-5W30","quantity":1},{"sku":"FILTER-OIL-001","quantity":1}]'),
 ('00000000-0000-0000-0000-000000000262','00000000-0000-0000-0000-000000000260','PRIVATE_WORK','Private workspace',60,'[]')
on conflict(id) do nothing;
insert into stock_balance(hub_id,sku,usable_quantity,reserved_quantity)
select h.id,s.sku,40,0 from hub h cross join (values('OIL-5W30'),('FILTER-OIL-001')) s(sku)
where h.id in ('00000000-0000-0000-0000-000000000160','00000000-0000-0000-0000-000000000260')
on conflict(hub_id,sku) do nothing;
commit;
