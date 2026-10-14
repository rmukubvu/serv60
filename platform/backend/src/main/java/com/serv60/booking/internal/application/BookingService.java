package com.serv60.booking.internal.application;

import com.serv60.booking.api.BookingContracts;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.HexFormat;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BookingService {
  private final JdbcClient db;
  private final ObjectMapper json;
  private final Clock clock;
  private final com.serv60.hubs.api.HubSchedule schedule;

  public BookingService(JdbcClient db, ObjectMapper json, Clock clock, com.serv60.hubs.api.HubSchedule schedule) {
    this.db = db;
    this.json = json;
    this.clock = clock;
    this.schedule = schedule;
  }

  public List<BookingContracts.Availability> availability(UUID hubId, String serviceCode,
                                                           OffsetDateTime from,
                                                           OffsetDateTime to) {
    var zone = java.time.ZoneId.of(db.sql("select timezone from hub where id=:hub").param("hub", hubId).query(String.class).optional().orElseThrow(() -> new IllegalArgumentException("Hub not found")));
    var local = from.atZoneSameInstant(zone);
    var hours = schedule.day(hubId, local.toLocalDate());
    if (hours.closed() || local.toLocalTime().isBefore(hours.opensAt())) return List.of();
    var closing = local.toLocalDate().atTime(hours.closesAt()).atZone(zone).toOffsetDateTime();
    return db.sql("""
        select h.id hub_id, st.code service_code, :from starts_at,
               (:from + make_interval(mins => st.duration_minutes)) ends_at,
               case when coalesce(sum(sb.usable_quantity - sb.reserved_quantity), 0) >=
                    coalesce(sum((part->>'quantity')::int), 0) then 'READY_STOCK'
                    else 'CONDITIONAL_PROCUREMENT' end readiness_policy
          from hub h join service_template st on st.hub_id = h.id
          left join lateral jsonb_array_elements(st.required_parts) part on true
          left join stock_balance sb on sb.hub_id = h.id and sb.sku = part->>'sku'
         where h.id = :hubId and st.code = :serviceCode and st.active = true
           and :from + make_interval(mins => st.duration_minutes) <= :closing
           and exists (
             select 1 from resource available_resource
              where available_resource.hub_id = h.id
                and available_resource.active = true
                and available_resource.kind = case
                  when st.code = 'MASSAGE' then 'MASSAGE_ROOM'
                  when st.code = 'BARBER' then 'BARBER_CHAIR'
                  when st.code = 'PRIVATE_WORK' then 'WORKSPACE'
                  else 'EXPRESS_BAY' end
                and not exists (
                  select 1 from resource_allocation occupied
                   where occupied.resource_id = available_resource.id
                     and occupied.status in ('HELD', 'CONFIRMED')
                     and occupied.starts_at < (:from + make_interval(mins => st.duration_minutes))
                     and occupied.ends_at > :from)
           )
         group by h.id, st.code, st.duration_minutes
        """)
        .param("hubId", hubId).param("serviceCode", serviceCode).param("from", from).param("closing", closing)
        .query((rs, n) -> new BookingContracts.Availability(
            rs.getObject("hub_id", UUID.class), rs.getString("service_code"),
            rs.getObject("starts_at", OffsetDateTime.class),
            rs.getObject("ends_at", OffsetDateTime.class), rs.getString("readiness_policy")))
        .list();
  }

  /** Returns car-service candidates only when the selected complimentary resource also fits. */
  public List<BookingContracts.Availability> availability(UUID hubId, String serviceCode,
      OffsetDateTime from, OffsetDateTime to, String complimentaryServiceCode) {
    int minutes = complimentaryDuration(hubId, serviceCode, complimentaryServiceCode);
    var candidates = availability(hubId, serviceCode, from, to);
    if (minutes == 0) return candidates;
    return candidates.stream().filter(slot -> !slot.startsAt().plusMinutes(minutes).isAfter(slot.endsAt())
        && db.sql("""
            select exists (select 1 from resource r
              where r.hub_id=:hub and r.kind=:kind and r.active=true
                and not exists (select 1 from resource_allocation a where a.resource_id=r.id
                  and a.status in ('HELD','CONFIRMED') and a.starts_at < :ends and a.ends_at > :starts))
            """).param("hub", hubId).param("kind", resourceKind(complimentaryServiceCode))
            .param("starts", slot.startsAt()).param("ends", slot.startsAt().plusMinutes(minutes))
            .query(Boolean.class).single()).toList();
  }

  int complimentaryDuration(UUID hubId, String serviceCode, String option) {
    if (option == null) return 0;
    if (!"EXPRESS_OIL".equals(serviceCode) || !("BARBER".equals(option) || "MASSAGE".equals(option) || "PRIVATE_WORK".equals(option))) {
      throw new IllegalArgumentException("Choose one complimentary option with an express vehicle service");
    }
    int minutes = db.sql("select duration_minutes from service_template where hub_id=:hub and code=:code and active=true")
        .param("hub", hubId).param("code", option).query(Integer.class).optional()
        .orElseThrow(() -> new IllegalArgumentException("Complimentary option is not available at this hub"));
    if (minutes > 60) throw new IllegalArgumentException("Complimentary option must fit within the service hour");
    return minutes;
  }

  private String resourceKind(String serviceCode) {
    return switch (serviceCode) {
      case "BARBER" -> "BARBER_CHAIR";
      case "MASSAGE" -> "MASSAGE_ROOM";
      case "PRIVATE_WORK" -> "WORKSPACE";
      default -> "EXPRESS_BAY";
    };
  }

  public List<BookingContracts.PartsReadiness> partsReadiness(UUID hubId, int limit) {
    if (limit < 1 || limit > 200) throw new IllegalArgumentException("limit must be between 1 and 200");
    return db.sql("""
        select sb.sku, sb.usable_quantity, sb.reserved_quantity,
               coalesce(sum(pd.quantity) filter (where pd.status='PENDING'), 0) pending_demand
          from stock_balance sb
          left join parts_demand pd on pd.hub_id=sb.hub_id and pd.sku=sb.sku
         where sb.hub_id=:hub
         group by sb.sku, sb.usable_quantity, sb.reserved_quantity
         order by sb.sku
         limit :limit
        """)
        .param("hub", hubId).param("limit", limit)
        .query((rs, row) -> new BookingContracts.PartsReadiness(rs.getString("sku"),
            rs.getInt("usable_quantity"), rs.getInt("reserved_quantity"), rs.getInt("pending_demand")))
        .list();
  }

  public List<BookingContracts.CalendarEntry> calendar(UUID hubId, OffsetDateTime from,
                                                        OffsetDateTime to) {
    if (to.isBefore(from) || to.equals(from)) {
      throw new IllegalArgumentException("to must be after from");
    }
    if (to.isAfter(from.plusDays(31))) {
      throw new IllegalArgumentException("calendar range cannot exceed 31 days");
    }
    return db.sql("""
        select ra.id allocation_id, ra.hold_id, r.id resource_id, r.code resource_code,
               case when r.kind='MASSAGE_ROOM' then 'MASSAGE' when r.kind='BARBER_CHAIR' then 'BARBER' when r.kind='WORKSPACE' then 'PRIVATE_WORK' else st.code end service_code, ra.starts_at, ra.ends_at, ra.status
          from resource_allocation ra
          join resource r on r.id=ra.resource_id
          join visit_hold vh on vh.id=ra.hold_id
          join service_template st on st.id=vh.service_template_id
         where vh.hub_id=:hub and ra.starts_at < :to and ra.ends_at > :from
           and ra.status in ('HELD','CONFIRMED')
         order by ra.starts_at, r.code
        """)
        .param("hub", hubId).param("from", from).param("to", to)
        .query((rs, row) -> new BookingContracts.CalendarEntry(
            rs.getObject("allocation_id", UUID.class), rs.getObject("hold_id", UUID.class),
            rs.getObject("resource_id", UUID.class), rs.getString("resource_code"),
            rs.getString("service_code"), rs.getObject("starts_at", OffsetDateTime.class),
            rs.getObject("ends_at", OffsetDateTime.class), rs.getString("status")))
        .list();
  }

  /** Reads appointment contact information within the operator's authorized hub. */
  public Optional<BookingContracts.AppointmentDetails> appointmentDetails(UUID hubId, UUID holdId) {
    return db.sql("select id, appointment_contact, complimentary_service_code from visit_hold where id=:id and hub_id=:hub")
        .param("id", holdId).param("hub", hubId)
        .query((rs, row) -> new BookingContracts.AppointmentDetails(rs.getObject("id", UUID.class),
            rs.getString("appointment_contact") == null ? null :
                json.readValue(rs.getString("appointment_contact"), BookingContracts.AppointmentContact.class),
            rs.getString("complimentary_service_code"))).optional();
  }

  public Optional<BookingContracts.Hold> findHold(UUID id) {
    return db.sql("select vh.*, st.code service_code from visit_hold vh join service_template st on st.id=vh.service_template_id where vh.id=:id")
        .param("id", id).query(this::mapHoldWithCode).optional();
  }

  @Transactional
  public BookingContracts.Hold createHold(BookingContracts.CreateHold request, String key) {
    if (key == null || key.isBlank()) throw new IllegalArgumentException("Idempotency-Key is required");
    if (request.endsAt().isBefore(request.startsAt()) || request.endsAt().equals(request.startsAt())) {
      throw new IllegalArgumentException("endsAt must be after startsAt");
    }
    if ("EXPRESS_OIL".equals(request.serviceCode()) &&
        !request.startsAt().plusHours(1).isEqual(request.endsAt())) {
      throw new IllegalArgumentException("Express vehicle service must last exactly one hour");
    }
    var requestHash = requestHash(request);
    var existing = db.sql("select vh.*, st.code service_code from visit_hold vh join service_template st on st.id=vh.service_template_id where vh.customer_id=:customerId and vh.idempotency_key=:key")
        .param("customerId", request.customerId()).param("key", key)
        .query(this::mapExistingHold).optional();
    if (existing.isPresent()) {
      if (existing.get().requestHash() != null && !existing.get().requestHash().equals(requestHash)) {
        throw new IllegalStateException("Idempotency-Key was reused with a different request");
      }
      return existing.get().hold();
    }

    int complimentaryMinutes = complimentaryDuration(request.hubId(), request.serviceCode(), request.complimentaryServiceCode());
    var service = db.sql("select id, duration_minutes, required_parts from service_template where hub_id=:hubId and code=:code and active=true")
        .param("hubId", request.hubId()).param("code", request.serviceCode())
        .query((rs, n) -> new ServiceRow(rs.getObject("id", UUID.class), rs.getInt("duration_minutes"), rs.getString("required_parts")))
        .optional().orElseThrow(() -> new IllegalArgumentException("Unknown active service"));
    var id = UUID.randomUUID();
    // Match PostgreSQL precision so the initial and replayed responses agree.
    var expires = OffsetDateTime.now(clock).plusMinutes(5).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    var policy = request.policy() == null || request.policy().isBlank() ? "READY_STOCK" : request.policy();
    if (!policy.equals("READY_STOCK") && !policy.equals("CONDITIONAL_PROCUREMENT")) {
      throw new IllegalArgumentException("Unsupported stock policy");
    }
    db.sql("""
        insert into visit_hold(id, hub_id, customer_id, vehicle_id, service_template_id,
          starts_at, ends_at, status, policy, expires_at, idempotency_key, request_hash, complimentary_service_code, appointment_contact)
        values (:id,:hub,:customer,:vehicle,:service,:starts,:ends,'HELD',:policy,:expires,:key,:requestHash,:complimentary,cast(:contact as jsonb))
        """).param("id", id).param("hub", request.hubId()).param("customer", request.customerId())
        .param("vehicle", request.vehicleId()).param("service", service.id())
        .param("starts", request.startsAt()).param("ends", request.endsAt()).param("policy", policy)
        .param("expires", expires).param("key", key).param("requestHash", requestHash).param("complimentary", request.complimentaryServiceCode())
        .param("contact", request.contact() == null ? null : json.writeValueAsString(request.contact())).update();
    allocateResource(id, request.hubId(), request.serviceCode(), request.startsAt(), request.endsAt());
    if (complimentaryMinutes > 0) {
      allocateResource(id, request.hubId(), request.complimentaryServiceCode(), request.startsAt(),
          request.startsAt().plusMinutes(complimentaryMinutes));
    }
    if (policy.equals("READY_STOCK")) reserveParts(service.requiredParts(), id, request.hubId());
    else recordPartsDemand(service.requiredParts(), id, request.hubId());
    db.sql("insert into outbox_event(id,event_type,aggregate_id,payload) values (:id,'VisitBundleHeld',:aggregate,cast(:payload as jsonb))")
        .param("id", UUID.randomUUID()).param("aggregate", id)
        .param("payload", "{\"holdId\":\"" + id + "\",\"policy\":\"" + policy + "\"}").update();
    return new BookingContracts.Hold(id, request.hubId(), request.customerId(), request.serviceCode(),
        request.startsAt(), request.endsAt(), "HELD", policy, expires, request.complimentaryServiceCode());
  }

  void allocateResource(UUID holdId, UUID hubId, String serviceCode,
                                OffsetDateTime startsAt, OffsetDateTime endsAt) {
    schedule.requireOpen(hubId, startsAt, endsAt);
    var resource = db.sql("""
        select r.id from resource r where r.hub_id=:hub and r.kind=:kind and r.active=true
          and not exists (select 1 from resource_allocation a where a.resource_id=r.id
            and a.status in ('HELD','CONFIRMED') and a.starts_at < :ends and a.ends_at > :starts)
        order by r.code limit 1 for update skip locked
        """).param("hub", hubId).param("kind", resourceKind(serviceCode)).param("starts", startsAt).param("ends", endsAt)
        .query((rs, n) -> rs.getObject("id", UUID.class)).optional()
        .orElseThrow(() -> new IllegalStateException("No compatible resource is available"));
    // Recheck with a fresh READ COMMITTED snapshot after acquiring the resource lock.
    // A competing allocation may have committed while the selection query was running.
    boolean occupied = db.sql("""
        select exists (select 1 from resource_allocation where resource_id=:resource
          and status in ('HELD','CONFIRMED') and starts_at < :ends and ends_at > :starts)
        """).param("resource", resource).param("starts", startsAt).param("ends", endsAt)
        .query(Boolean.class).single();
    if (occupied) throw new IllegalStateException("Resource was just reserved; please check availability again");
    db.sql("insert into resource_allocation(id,hold_id,resource_id,starts_at,ends_at,status) values (:id,:hold,:resource,:starts,:ends,'HELD')")
        .param("id", UUID.randomUUID()).param("hold", holdId).param("resource", resource)
        .param("starts", startsAt).param("ends", endsAt).update();
  }

  private void recordPartsDemand(String rawParts, UUID holdId, UUID hubId) {
    try {
      for (JsonNode part : json.readTree(rawParts)) {
        db.sql("insert into parts_demand(id,hold_id,hub_id,sku,quantity,status) values (:id,:hold,:hub,:sku,:quantity,'PENDING')")
            .param("id", UUID.randomUUID()).param("hold", holdId).param("hub", hubId)
            .param("sku", part.path("sku").asText()).param("quantity", part.path("quantity").asInt()).update();
      }
    } catch (RuntimeException error) { throw new IllegalStateException("Invalid service parts definition", error); }
  }

  private void reserveParts(String rawParts, UUID holdId, UUID hubId) {
    try {
      JsonNode parts = json.readTree(rawParts);
      for (JsonNode part : parts) {
        String sku = part.path("sku").asText(); int quantity = part.path("quantity").asInt();
        int updated = db.sql("update stock_balance set reserved_quantity=reserved_quantity+:quantity where hub_id=:hub and sku=:sku and usable_quantity-reserved_quantity>=:quantity")
            .param("quantity", quantity).param("hub", hubId).param("sku", sku).update();
        if (updated != 1) throw new IllegalStateException("Required part is not ready: " + sku);
        db.sql("insert into stock_reservation(id,hold_id,hub_id,sku,quantity,status) values (:id,:hold,:hub,:sku,:quantity,'HELD')")
            .param("id", UUID.randomUUID()).param("hold", holdId).param("hub", hubId).param("sku", sku).param("quantity", quantity).update();
      }
    } catch (RuntimeException error) { throw new IllegalStateException("Invalid service parts definition", error); }
  }

  /** Confirms an unexpired hold; used by operators and by customers who already paid on time. */
  @Transactional
  public BookingContracts.Hold confirm(UUID id) {
    return confirm(id, false);
  }

  /**
   * Confirms a hold whose payment the provider has settled. The hold's resources stay allocated
   * until the expiry sweep releases them, so a settled hold past its display expiry is still safe
   * to confirm. Replays of an already confirmed hold return it unchanged.
   */
  @Transactional
  public BookingContracts.Hold confirmSettled(UUID id) {
    return confirm(id, true);
  }

  private BookingContracts.Hold confirm(UUID id, boolean settled) {
    var hold = db.sql("select vh.*, st.code service_code from visit_hold vh join service_template st on st.id=vh.service_template_id where vh.id=:id for update")
        .param("id", id).query(this::mapHoldWithCode).optional()
        .orElseThrow(() -> new IllegalArgumentException("Visit hold not found"));
    if (settled && hold.status().equals("CONFIRMED")) return hold;
    boolean expired = hold.expiresAt().isBefore(OffsetDateTime.now(clock));
    if (!hold.status().equals("HELD") || (expired && !settled)) {
      throw new IllegalStateException("Hold expired or no longer confirmable");
    }
    db.sql("update visit_hold set status='CONFIRMED' where id=:id").param("id", id).update();
    db.sql("update resource_allocation set status='CONFIRMED' where hold_id=:id").param("id", id).update();
    db.sql("update stock_reservation set status='CONFIRMED' where hold_id=:id").param("id", id).update();
    return new BookingContracts.Hold(id, hold.hubId(), hold.customerId(), hold.serviceCode(), hold.startsAt(), hold.endsAt(), "CONFIRMED", hold.policy(), hold.expiresAt(), hold.complimentaryServiceCode());
  }

  @Transactional
  public BookingContracts.Hold cancel(UUID id) {
    var hold = db.sql("select vh.*, st.code service_code from visit_hold vh join service_template st on st.id=vh.service_template_id where vh.id=:id for update")
        .param("id", id).query(this::mapHoldWithCode).optional().orElseThrow();
    if (hold.status().equals("CANCELLED") || hold.status().equals("EXPIRED")) return hold;
    if (!hold.status().equals("HELD") && !hold.status().equals("CONFIRMED")) {
      throw new IllegalStateException("Visit hold is no longer cancellable");
    }
    db.sql("""
        update stock_balance sb set reserved_quantity = reserved_quantity - sr.quantity
          from stock_reservation sr
         where sr.hold_id=:id and sr.status in ('HELD','CONFIRMED')
           and sb.hub_id=sr.hub_id and sb.sku=sr.sku
        """).param("id", id).update();
    db.sql("update stock_reservation set status='RELEASED' where hold_id=:id and status in ('HELD','CONFIRMED')")
        .param("id", id).update();
    db.sql("update parts_demand set status='CANCELLED' where hold_id=:id and status='PENDING'")
        .param("id", id).update();
    db.sql("update resource_allocation set status='RELEASED' where hold_id=:id and status in ('HELD','CONFIRMED')")
        .param("id", id).update();
    db.sql("update visit_hold set status='CANCELLED' where id=:id").param("id", id).update();
    db.sql("insert into outbox_event(id,event_type,aggregate_id,payload) values (:id,'VisitHoldCancelled',:aggregate,cast(:payload as jsonb))")
        .param("id", UUID.randomUUID()).param("aggregate", id)
        .param("payload", "{\"holdId\":\"" + id + "\"}").update();
    return new BookingContracts.Hold(id, hold.hubId(), hold.customerId(), hold.serviceCode(), hold.startsAt(), hold.endsAt(), "CANCELLED", hold.policy(), hold.expiresAt(), hold.complimentaryServiceCode());
  }

  @Transactional
  public int expireDueHolds(OffsetDateTime now) {
    var ids = db.sql("select id from visit_hold where status='HELD' and expires_at <= :now for update")
        .param("now", now).query((rs, row) -> rs.getObject("id", UUID.class)).list();
    for (UUID id : ids) {
      db.sql("""
          update stock_balance sb set reserved_quantity = reserved_quantity - sr.quantity
            from stock_reservation sr
           where sr.hold_id=:id and sr.status='HELD' and sb.hub_id=sr.hub_id and sb.sku=sr.sku
          """).param("id", id).update();
      db.sql("update stock_reservation set status='RELEASED' where hold_id=:id and status='HELD'")
          .param("id", id).update();
      db.sql("update parts_demand set status='CANCELLED' where hold_id=:id and status='PENDING'")
          .param("id", id).update();
      db.sql("update resource_allocation set status='RELEASED' where hold_id=:id and status='HELD'")
          .param("id", id).update();
      db.sql("update visit_hold set status='EXPIRED' where id=:id and status='HELD'")
          .param("id", id).update();
      db.sql("insert into outbox_event(id,event_type,aggregate_id,payload) values (:id,'VisitHoldExpired',:aggregate,cast(:payload as jsonb))")
          .param("id", UUID.randomUUID()).param("aggregate", id)
          .param("payload", "{\"holdId\":\"" + id + "\"}").update();
    }
    return ids.size();
  }

  private BookingContracts.Hold mapHoldWithCode(ResultSet rs, int row) throws SQLException {
    return new BookingContracts.Hold(rs.getObject("id", UUID.class), rs.getObject("hub_id", UUID.class),
        rs.getObject("customer_id", UUID.class), rs.getString("service_code"), rs.getObject("starts_at", OffsetDateTime.class),
        rs.getObject("ends_at", OffsetDateTime.class), rs.getString("status"), rs.getString("policy"), rs.getObject("expires_at", OffsetDateTime.class), rs.getString("complimentary_service_code"));
  }
  private ExistingHold mapExistingHold(ResultSet rs, int row) throws SQLException {
    return new ExistingHold(mapHoldWithCode(rs, row), rs.getString("request_hash"));
  }
  private String requestHash(BookingContracts.CreateHold request) {
    var canonical = String.join("\u001f", request.hubId().toString(), request.customerId().toString(),
        request.vehicleId() == null ? "" : request.vehicleId().toString(), request.serviceCode(),
        request.startsAt().toString(), request.endsAt().toString(), request.policy() == null ? "" : request.policy());
    // Keep hashes of legacy requests unchanged across the additive rollout.
    if (request.complimentaryServiceCode() != null) canonical += "\u001f" + request.complimentaryServiceCode();
    if (request.contact() != null) canonical += "\u001fcontact:" + json.writeValueAsString(request.contact());
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is unavailable", error);
    }
  }
  private record ExistingHold(BookingContracts.Hold hold, String requestHash) {}
  private record ServiceRow(UUID id, int duration, String requiredParts) {}
}
