-- V18 seeded five illustrative Cape Town tow partners into every environment, so production would
-- have quoted real customers prices from companies that do not exist. Remove them where nothing
-- references them; development databases that already quoted them keep the rows, offline.
-- Local demos load the same fleet from platform/infra/seed-roadside-partners.sql.
delete from roadside_partner p
 where p.id in ('a0000000-0000-0000-0000-000000000001','a0000000-0000-0000-0000-000000000002','a0000000-0000-0000-0000-000000000003','a0000000-0000-0000-0000-000000000004','a0000000-0000-0000-0000-000000000005')
   and not exists (select 1 from roadside_quote q where q.partner_id = p.id)
   and not exists (select 1 from roadside_dispatch_offer o where o.partner_id = p.id);
update roadside_partner set online = false where id in ('a0000000-0000-0000-0000-000000000001','a0000000-0000-0000-0000-000000000002','a0000000-0000-0000-0000-000000000003','a0000000-0000-0000-0000-000000000004','a0000000-0000-0000-0000-000000000005');
