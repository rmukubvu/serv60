package com.serv60.hubs.internal.application;

import com.serv60.errors.Conflict;
import com.serv60.errors.NotFound;
import com.serv60.hubs.api.HubSettingsContracts.*;
import com.serv60.hubs.api.ResourceKind;
import com.serv60.hubs.api.ServiceCode;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Owns hub capacity changes and preserves every existing active allocation. */
@Service
public class HubSettingsService implements com.serv60.hubs.api.HubConfiguration {
  private final JdbcClient db;
  private final ObjectMapper json;
  private final Clock clock;

  public HubSettingsService(JdbcClient db, ObjectMapper json, Clock clock) {
    this.db = db;
    this.json = json;
    this.clock = clock;
  }

  /** Returns a consistent configuration snapshot, including idle bookable resources. */
  @Transactional(timeout = 10)
  public Settings read(UUID hub) {
    db.sql("select id from hub where id=:hub for share")
        .param("hub", hub)
        .query(UUID.class)
        .optional()
        .orElseThrow(() -> new NotFound("Hub not found"));
    var resources =
        db.sql(
                "select id,code,kind from resource where hub_id=:hub and active=true order by"
                    + " kind,code limit 501")
            .param("hub", hub)
            .query(
                (rs, n) ->
                    new Resource(
                        rs.getObject("id", UUID.class), rs.getString("code"), rs.getString("kind")))
            .list();
    if (resources.size() > 500)
      throw new Conflict("Hub resource limit exceeded; contact support");
    var hours =
        db.sql(
                """
                select days.day, coalesce(d.closed,false) closed, coalesce(d.opens_at,h.opens_at) opens_at,
                  coalesce(d.closes_at,h.closes_at) closes_at from hub h cross join generate_series(1,7) days(day)
                  left join hub_trading_day d on d.hub_id=h.id and d.day_of_week=days.day
                  where h.id=:hub order by days.day
                """)
            .param("hub", hub)
            .query(
                (rs, n) ->
                    new TradingDay(
                        rs.getInt("day"),
                        rs.getBoolean("closed"),
                        rs.getObject("opens_at", LocalTime.class),
                        rs.getObject("closes_at", LocalTime.class)))
            .list();
    return db.sql("select name,timezone,published,settings_revision from hub where id=:hub")
        .param("hub", hub)
        .query(
            (rs, n) ->
                new Settings(
                    hub,
                    rs.getString("name"),
                    rs.getString("timezone"),
                    rs.getBoolean("published"),
                    rs.getLong("settings_revision"),
                    new Capacity(
                        count(resources, ResourceKind.EXPRESS_BAY),
                        count(resources, ResourceKind.BARBER_CHAIR),
                        count(resources, ResourceKind.MASSAGE_ROOM),
                        count(resources, ResourceKind.WORKSPACE)),
                    hours,
                    resources))
        .single();
  }

  /**
   * Atomically updates weekly hours and physical capacity, with optimistic concurrency and retry
   * safety.
   */
  @Transactional(timeout = 10)
  public Settings update(UUID hub, Update request) {
    db.sql("select id from hub where id=:hub for update")
        .param("hub", hub)
        .query(UUID.class)
        .optional()
        .orElseThrow(() -> new NotFound("Hub not found"));
    var before = read(hub);
    boolean unchanged =
        before.capacity().equals(request.capacity())
            && before.tradingHours().equals(request.tradingHours());
    if (unchanged) return before; // A lost-response retry never creates a second event.
    if (before.revision() != request.revision())
      throw new Conflict(
          "Hub settings changed in another session. Reload before saving");
    protectAppointments(hub, request);
    var c = request.capacity();
    resize(hub, ResourceKind.EXPRESS_BAY, c.expressBays());
    resize(hub, ResourceKind.BARBER_CHAIR, c.barberChairs());
    resize(hub, ResourceKind.MASSAGE_ROOM, c.massageRooms());
    resize(hub, ResourceKind.WORKSPACE, c.workspaces());
    configureService(hub, ServiceCode.BARBER, "Barber", 45, c.barberChairs());
    configureService(hub, ServiceCode.MASSAGE, "Massage", 45, c.massageRooms());
    configureService(hub, ServiceCode.PRIVATE_WORK, "Private workspace", 60, c.workspaces());
    for (var day : request.tradingHours()) {
      db.sql(
              """
              insert into hub_trading_day(hub_id,day_of_week,closed,opens_at,closes_at) values (:hub,:day,:closed,:opens,:closes)
              on conflict(hub_id,day_of_week) do update set closed=excluded.closed,opens_at=excluded.opens_at,closes_at=excluded.closes_at
              """)
          .param("hub", hub)
          .param("day", day.dayOfWeek())
          .param("closed", day.closed())
          .param("opens", day.opensAt())
          .param("closes", day.closesAt())
          .update();
    }
    db.sql("update hub set settings_revision=settings_revision+1 where id=:hub")
        .param("hub", hub)
        .update();
    db.sql(
            "insert into outbox_event(id,event_type,aggregate_id,payload) values"
                + " (:id,'HubSettingsChanged',:hub,cast(:payload as jsonb))")
        .param("id", UUID.randomUUID())
        .param("hub", hub)
        .param(
            "payload",
            json.writeValueAsString(Map.of("hubId", hub, "revision", before.revision() + 1)))
        .update();
    return read(hub);
  }

  private void protectAppointments(UUID hub, Update request) {
    boolean conflict =
        db.sql(
                """
                select exists (select 1 from resource_allocation a join resource r on r.id=a.resource_id
                  join hub h on h.id=r.hub_id
                  join jsonb_to_recordset(cast(:hours as jsonb)) as d("dayOfWeek" int, closed boolean, "opensAt" time, "closesAt" time)
                    on d."dayOfWeek"=extract(isodow from a.starts_at at time zone h.timezone)
                  where r.hub_id=:hub and a.status in ('HELD','CONFIRMED') and a.ends_at>:now
                  and (d.closed or (a.starts_at at time zone h.timezone)::date<>(a.ends_at at time zone h.timezone)::date
                    or (a.starts_at at time zone h.timezone)::time<d."opensAt"
                    or (a.ends_at at time zone h.timezone)::time>d."closesAt"))
                """)
            .param("hub", hub)
            .param("hours", json.writeValueAsString(request.tradingHours()))
            .param("now", OffsetDateTime.now(clock))
            .query(Boolean.class)
            .single();
    if (conflict)
      throw new Conflict(
          "Trading hours conflict with an upcoming booking. Move that booking before changing these"
              + " hours");
  }

  private void resize(UUID hub, ResourceKind resourceKind, int desired) {
    var kind = resourceKind.name();
    var prefix = resourceKind.codePrefix();
    var rows =
        db.sql(
                """
                select r.id,r.active,exists(select 1 from resource_allocation a where a.resource_id=r.id
                  and a.status in ('HELD','CONFIRMED') and a.ends_at>:now) occupied
                  from resource r where r.hub_id=:hub and r.kind=:kind order by r.code limit 501
                """)
            .param("hub", hub)
            .param("kind", kind)
            .param("now", OffsetDateTime.now(clock))
            .query(
                (rs, n) ->
                    new ResourceState(
                        rs.getObject("id", UUID.class),
                        rs.getBoolean("active"),
                        rs.getBoolean("occupied")))
            .list();
    if (rows.size() > 500)
      throw new Conflict("Hub resource limit exceeded; contact support");
    int active = (int) rows.stream().filter(ResourceState::active).count();
    if (active > desired) {
      var removable = rows.stream().filter(r -> r.active() && !r.occupied()).toList();
      if (removable.size() < active - desired)
        throw new Conflict(
            "Cannot reduce "
                + kind.toLowerCase(Locale.ROOT).replace('_', ' ')
                + " capacity: resources have upcoming bookings");
      for (var r : removable.subList(0, active - desired)) setActive(r.id(), false);
    } else {
      for (var r : rows) {
        if (active >= desired) break;
        if (!r.active()) {
          setActive(r.id(), true);
          active++;
        }
      }
      for (int index = 1; active < desired && index <= 500; index++) {
        int added =
            db.sql(
                    "insert into resource(id,hub_id,code,kind) values (:id,:hub,:code,:kind) on"
                        + " conflict(hub_id,code) do nothing")
                .param("id", UUID.randomUUID())
                .param("hub", hub)
                .param("code", prefix + "-" + String.format(Locale.ROOT, "%02d", index))
                .param("kind", kind)
                .update();
        active += added;
      }
      if (active < desired)
        throw new Conflict("Resource naming limit reached; contact support");
    }
  }

  private void setActive(UUID id, boolean active) {
    db.sql("update resource set active=:active where id=:id")
        .param("id", id)
        .param("active", active)
        .update();
  }

  private void configureService(UUID hub, ServiceCode service, String name, int minutes, int capacity) {
    var code = service.name();
    db.sql(
            """
            insert into service_template(id,hub_id,code,name,duration_minutes,required_parts,active)
              values (:id,:hub,:code,:name,:minutes,'[]',:active)
              on conflict(hub_id,code) do update set active=excluded.active
            """)
        .param("id", UUID.randomUUID())
        .param("hub", hub)
        .param("code", code)
        .param("name", name)
        .param("minutes", minutes)
        .param("active", capacity > 0)
        .update();
  }

  private static int count(List<Resource> resources, ResourceKind kind) {
    return (int) resources.stream().filter(r -> r.kind().equals(kind.name())).count();
  }

  private record ResourceState(UUID id, boolean active, boolean occupied) {}
}
