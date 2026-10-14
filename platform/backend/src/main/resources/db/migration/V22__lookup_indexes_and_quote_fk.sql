-- V3 duplicated the index that the unique (customer_id, idempotency_key) constraint already has.
drop index if exists visit_hold_idempotency_lookup;

-- Foreign keys that confirm, cancel, change and expiry filter on, previously scanned in full.
-- Plain (locking) builds are acceptable at pilot table sizes; use CONCURRENTLY in a
-- non-transactional migration once these tables are large.
create index resource_allocation_hold on resource_allocation(hold_id);
create index stock_reservation_hold on stock_reservation(hold_id);
create index stock_reservation_hub on stock_reservation(hub_id);
create index parts_demand_hold on parts_demand(hold_id);
create index visit_hold_service_template on visit_hold(service_template_id);
create index roadside_quote_incident on roadside_quote(incident_id);
create index roadside_quote_partner on roadside_quote(partner_id);
create index roadside_dispatch_offer_partner on roadside_dispatch_offer(partner_id);

-- An incident's accepted quote must be a real quote. NOT VALID checks new and changed rows only.
alter table roadside_incident
  add constraint roadside_incident_selected_quote_fk
  foreign key (selected_quote_id) references roadside_quote(id) not valid;
create index roadside_incident_selected_quote on roadside_incident(selected_quote_id)
  where selected_quote_id is not null;
