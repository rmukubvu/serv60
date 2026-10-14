-- Roadside incidents belong to the hub they are towed to, so each hub's operators see only their
-- own customers. Null means no published hub existed when the incident was raised; those stay
-- visible to every hub's board so a stranded customer is never hidden.
alter table roadside_incident add column hub_id uuid references hub(id);

update roadside_incident i
   set hub_id = (
     select h.id from hub h
      where h.published and h.latitude is not null
      order by 6371.0088 * acos(least(1.0, greatest(-1.0,
        sin(radians(i.destination_latitude))*sin(radians(h.latitude)) +
        cos(radians(i.destination_latitude))*cos(radians(h.latitude))
          *cos(radians(h.longitude-i.destination_longitude)))))
      limit 1)
 where i.destination_latitude is not null and i.destination_longitude is not null;

create index roadside_incident_hub_board on roadside_incident(hub_id, created_at);
