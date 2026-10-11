package com.serv60.booking.internal.application;

import com.serv60.booking.api.BookingContracts;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
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

  public BookingService(JdbcClient db, ObjectMapper json) { this.db = db; this.json = json; }

  public List<BookingContracts.Availability> availability(UUID hubId, String serviceCode,
                                                           OffsetDateTime from,
                                                           OffsetDateTime to) {
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
         group by h.id, st.code, st.duration_minutes
        """)
        .param("hubId", hubId).param("serviceCode", serviceCode).param("from", from)
        .query((rs, n) -> new BookingContracts.Availability(
            rs.getObject("hub_id", UUID.class), rs.getString("service_code"),
            rs.getObject("starts_at", OffsetDateTime.class),
            rs.getObject("ends_at", OffsetDateTime.class), rs.getString("readiness_policy")))
        .list();
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

  @Transactional
  public BookingContracts.Hold createHold(BookingContracts.CreateHold request, String key) {
    if (key == null || key.isBlank()) throw new IllegalArgumentException("Idempotency-Key is required");
    if (request.endsAt().isBefore(request.startsAt()) || request.endsAt().equals(request.startsAt())) {
      throw new IllegalArgumentException("endsAt must be after startsAt");
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

    var service = db.sql("select id, duration_minutes, required_parts from service_template where hub_id=:hubId and code=:code and active=true")
        .param("hubId", request.hubId()).param("code", request.serviceCode())
        .query((rs, n) -> new ServiceRow(rs.getObject("id", UUID.class), rs.getInt("duration_minutes"), rs.getString("required_parts")))
        .optional().orElseThrow(() -> new IllegalArgumentException("Unknown active service"));
    var id = UUID.randomUUID();
    var expires = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5);
    var policy = request.policy() == null || request.policy().isBlank() ? "READY_STOCK" : request.policy();
    if (!policy.equals("READY_STOCK") && !policy.equals("CONDITIONAL_PROCUREMENT")) {
      throw new IllegalArgumentException("Unsupported stock policy");
    }
    db.sql("""
        insert into visit_hold(id, hub_id, customer_id, vehicle_id, service_template_id,
          starts_at, ends_at, status, policy, expires_at, idempotency_key, request_hash)
        values (:id,:hub,:customer,:vehicle,:service,:starts,:ends,'HELD',:policy,:expires,:key,:requestHash)
        """).param("id", id).param("hub", request.hubId()).param("customer", request.customerId())
        .param("vehicle", request.vehicleId()).param("service", service.id())
        .param("starts", request.startsAt()).param("ends", request.endsAt()).param("policy", policy)
        .param("expires", expires).param("key", key).param("requestHash", requestHash).update();
    var resourceKind = request.serviceCode().equals("BARBER") ? "BARBER_CHAIR" :
        request.serviceCode().equals("PRIVATE_WORK") ? "WORKSPACE" : "EXPRESS_BAY";
    var resource = db.sql("""
        select r.id from resource r where r.hub_id=:hub and r.kind=:kind and r.active=true
          and not exists (select 1 from resource_allocation a where a.resource_id=r.id
            and a.status in ('HELD','CONFIRMED') and a.starts_at < :ends and a.ends_at > :starts)
        order by r.code limit 1 for update skip locked
        """).param("hub", request.hubId()).param("kind", resourceKind).param("starts", request.startsAt()).param("ends", request.endsAt())
        .query((rs, n) -> rs.getObject("id", UUID.class)).optional()
        .orElseThrow(() -> new IllegalStateException("No compatible resource is available"));
    db.sql("insert into resource_allocation(id,hold_id,resource_id,starts_at,ends_at,status) values (:id,:hold,:resource,:starts,:ends,'HELD')")
        .param("id", UUID.randomUUID()).param("hold", id).param("resource", resource)
        .param("starts", request.startsAt()).param("ends", request.endsAt()).update();
    if (policy.equals("READY_STOCK")) reserveParts(service.requiredParts(), id, request.hubId());
    else recordPartsDemand(service.requiredParts(), id, request.hubId());
    db.sql("insert into outbox_event(id,event_type,aggregate_id,payload) values (:id,'VisitBundleHeld',:aggregate,cast(:payload as jsonb))")
        .param("id", UUID.randomUUID()).param("aggregate", id)
        .param("payload", "{\"holdId\":\"" + id + "\",\"policy\":\"" + policy + "\"}").update();
    return new BookingContracts.Hold(id, request.hubId(), request.customerId(), request.serviceCode(),
        request.startsAt(), request.endsAt(), "HELD", policy, expires);
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

  @Transactional
  public BookingContracts.Hold confirm(UUID id) {
    var hold = db.sql("select vh.*, st.code service_code from visit_hold vh join service_template st on st.id=vh.service_template_id where vh.id=:id for update")
        .param("id", id).query(this::mapHoldWithCode).optional().orElseThrow();
    if (!hold.status().equals("HELD") || hold.expiresAt().isBefore(OffsetDateTime.now(ZoneOffset.UTC))) {
      throw new IllegalStateException("Hold expired or no longer confirmable");
    }
    db.sql("update visit_hold set status='CONFIRMED' where id=:id").param("id", id).update();
    db.sql("update resource_allocation set status='CONFIRMED' where hold_id=:id").param("id", id).update();
    db.sql("update stock_reservation set status='CONFIRMED' where hold_id=:id").param("id", id).update();
    return new BookingContracts.Hold(id, hold.hubId(), hold.customerId(), hold.serviceCode(), hold.startsAt(), hold.endsAt(), "CONFIRMED", hold.policy(), hold.expiresAt());
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
        rs.getObject("ends_at", OffsetDateTime.class), rs.getString("status"), rs.getString("policy"), rs.getObject("expires_at", OffsetDateTime.class));
  }
  private ExistingHold mapExistingHold(ResultSet rs, int row) throws SQLException {
    return new ExistingHold(mapHoldWithCode(rs, row), rs.getString("request_hash"));
  }
  private String requestHash(BookingContracts.CreateHold request) {
    var canonical = String.join("\u001f", request.hubId().toString(), request.customerId().toString(),
        request.vehicleId() == null ? "" : request.vehicleId().toString(), request.serviceCode(),
        request.startsAt().toString(), request.endsAt().toString(), request.policy() == null ? "" : request.policy());
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is unavailable", error);
    }
  }
  private record ExistingHold(BookingContracts.Hold hold, String requestHash) {}
  private record ServiceRow(UUID id, int duration, String requiredParts) {}
}
