package com.serv60.booking.internal.application;

import com.serv60.errors.Conflict;
import com.serv60.errors.NotFound;
import com.serv60.booking.api.BookingContracts;
import com.serv60.booking.internal.domain.VisitStatus;
import com.serv60.hubs.api.ServiceCode;
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
public class BookingService implements com.serv60.booking.api.HubBookingBoard {
  private final JdbcClient db;
  private final ObjectMapper json;
  private final Clock clock;
  private final com.serv60.hubs.api.HubSchedule schedule;
  private final java.time.Duration holdDuration;
  private final io.micrometer.core.instrument.MeterRegistry metrics;

  public BookingService(JdbcClient db, ObjectMapper json, Clock clock, com.serv60.hubs.api.HubSchedule schedule,
                        @org.springframework.beans.factory.annotation.Value("${serv60.booking.hold-duration-seconds:900}")
                        long holdDurationSeconds,
                        io.micrometer.core.instrument.MeterRegistry metrics) {
    if (holdDurationSeconds < 60 || holdDurationSeconds > 3600) {
      throw new IllegalArgumentException("serv60.booking.hold-duration-seconds must be between 60 and 3600");
    }
    this.db = db;
    this.json = json;
    this.clock = clock;
    this.schedule = schedule;
    this.holdDuration = java.time.Duration.ofSeconds(holdDurationSeconds);
    this.metrics = metrics;
  }

  private static final java.time.Duration SLOT_STEP = java.time.Duration.ofMinutes(15);

  public List<BookingContracts.Availability> availability(UUID hubId, String serviceCode,
                                                           OffsetDateTime from,
                                                           OffsetDateTime to) {
    return availability(hubId, serviceCode, from, to, null);
  }

  /**
   * Lists every start in [from, to) on a 15-minute grid from each day's opening time where the
   * service, and the complimentary option when one is chosen, fit inside trading hours. The range
   * may span at most seven days; each hub day costs one query.
   */
  public List<BookingContracts.Availability> availability(UUID hubId, String serviceCode,
      OffsetDateTime from, OffsetDateTime to, String complimentaryServiceCode) {
    if (!to.isAfter(from)) throw new IllegalArgumentException("to must be after from");
    if (to.isAfter(from.plusDays(7))) {
      throw new IllegalArgumentException("availability range cannot exceed 7 days");
    }
    int optionMinutes = complimentaryDuration(hubId, serviceCode, complimentaryServiceCode);
    var zone = java.time.ZoneId.of(db.sql("select timezone from hub where id=:hub").param("hub", hubId)
        .query(String.class).optional().orElseThrow(() -> new NotFound("Hub not found")));
    var duration = db.sql("select duration_minutes from service_template where hub_id=:hub and code=:code and active=true")
        .param("hub", hubId).param("code", serviceCode).query(Integer.class).optional();
    if (duration.isEmpty()) return List.of();
    int minutes = duration.get();
    var readiness = readinessPolicy(hubId, serviceCode);
    // timestamptz keeps microseconds, so exclude `to` by one microsecond rather than one nanosecond.
    var lastRequested = to.minusNanos(1_000).atZoneSameInstant(zone);
    var slots = new java.util.ArrayList<BookingContracts.Availability>();
    for (var date = from.atZoneSameInstant(zone).toLocalDate(); !date.isAfter(lastRequested.toLocalDate());
         date = date.plusDays(1)) {
      var hours = schedule.day(hubId, date);
      if (hours.closed()) continue;
      var opens = date.atTime(hours.opensAt()).atZone(zone);
      var latestStart = date.atTime(hours.closesAt()).atZone(zone).minusMinutes(minutes);
      var first = alignToGrid(opens, from.atZoneSameInstant(zone));
      var last = latestStart.isBefore(lastRequested) ? latestStart : lastRequested;
      if (first.isAfter(last)) continue;
      slots.addAll(db.sql("""
          select candidate from generate_series(cast(:first as timestamptz), cast(:last as timestamptz),
                                                interval '15 minutes') candidate
           where exists (select 1 from resource r
                  where r.hub_id=:hub and r.active=true and r.kind=:kind
                    and not exists (select 1 from resource_allocation a where a.resource_id=r.id
                      and a.status in ('HELD','CONFIRMED')
                      and a.starts_at < candidate + make_interval(mins => :minutes) and a.ends_at > candidate))
             and (:optionMinutes=0 or exists (select 1 from resource r
                  where r.hub_id=:hub and r.active=true and r.kind=:optionKind
                    and not exists (select 1 from resource_allocation a where a.resource_id=r.id
                      and a.status in ('HELD','CONFIRMED')
                      and a.starts_at < candidate + make_interval(mins => :optionMinutes) and a.ends_at > candidate)))
           order by candidate
          """)
          .param("first", first.toOffsetDateTime()).param("last", last.toOffsetDateTime())
          .param("hub", hubId).param("kind", resourceKind(serviceCode)).param("minutes", minutes)
          .param("optionMinutes", optionMinutes)
          .param("optionKind", complimentaryServiceCode == null ? "" : resourceKind(complimentaryServiceCode))
          .query((rs, n) -> {
            var start = rs.getObject("candidate", OffsetDateTime.class).atZoneSameInstant(zone).toOffsetDateTime();
            return new BookingContracts.Availability(hubId, serviceCode, start, start.plusMinutes(minutes), readiness);
          })
          .list());
    }
    return slots;
  }

  /** Rounds up to the next 15-minute step counted from the day's opening time. */
  private static java.time.ZonedDateTime alignToGrid(java.time.ZonedDateTime opens,
                                                     java.time.ZonedDateTime requested) {
    if (!requested.isAfter(opens)) return opens;
    long step = SLOT_STEP.toNanos();
    long offset = java.time.Duration.between(opens, requested).toNanos();
    return opens.plusNanos(((offset + step - 1) / step) * step);
  }

  /** READY_STOCK only when every required SKU is individually available; surplus of one never covers another. */
  String readinessPolicy(UUID hub, String serviceCode) {
    return db.sql("""
        select case when exists (
          select 1 from service_template st cross join lateral jsonb_array_elements(st.required_parts) part
          left join stock_balance sb on sb.hub_id=st.hub_id and sb.sku=part->>'sku'
          where st.hub_id=:hub and st.code=:code and st.active=true
            and coalesce(sb.usable_quantity-sb.reserved_quantity,0)<(part->>'quantity')::int
        ) then 'CONDITIONAL_PROCUREMENT' else 'READY_STOCK' end
        """)
        .param("hub", hub).param("code", serviceCode).query(String.class).single();
  }

  int complimentaryDuration(UUID hubId, String serviceCode, String option) {
    if (option == null) return 0;
    if (!ServiceCode.EXPRESS_OIL.name().equals(serviceCode)
        || !ServiceCode.of(option).map(ServiceCode::complimentary).orElse(false)) {
      throw new IllegalArgumentException("Choose one complimentary option with an express vehicle service");
    }
    int minutes = db.sql("select duration_minutes from service_template where hub_id=:hub and code=:code and active=true")
        .param("hub", hubId).param("code", option).query(Integer.class).optional()
        .orElseThrow(() -> new IllegalArgumentException("Complimentary option is not available at this hub"));
    if (minutes > 60) throw new IllegalArgumentException("Complimentary option must fit within the service hour");
    return minutes;
  }

  private static String resourceKind(String serviceCode) {
    return ServiceCode.require(serviceCode).resourceKind().name();
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
               %s service_code, ra.starts_at, ra.ends_at, ra.status
          from resource_allocation ra
          join resource r on r.id=ra.resource_id
          join visit_hold vh on vh.id=ra.hold_id
          join service_template st on st.id=vh.service_template_id
         where vh.hub_id=:hub and ra.starts_at < :to and ra.ends_at > :from
           and ra.status in ('HELD','CONFIRMED')
         order by ra.starts_at, r.code
        """.formatted(ServiceCode.complimentaryCodeSql("r.kind", "st.code")))
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
    if (ServiceCode.EXPRESS_OIL.name().equals(request.serviceCode()) &&
        !request.startsAt().plusHours(1).isEqual(request.endsAt())) {
      throw new IllegalArgumentException("Express vehicle service must last exactly one hour");
    }
    var requestHash = requestHash(request);
    // Serialize same-key retries so the second waits and replays instead of hitting the unique key.
    // Same lock string as CustomerBookingService.create; advisory locks are reentrant per session.
    db.sql("select pg_advisory_xact_lock(hashtextextended(:lock,0))")
        .param("lock", request.customerId() + ":" + key).query((rs, row) -> true).single();
    var existing = db.sql("select vh.*, st.code service_code from visit_hold vh join service_template st on st.id=vh.service_template_id where vh.customer_id=:customerId and vh.idempotency_key=:key")
        .param("customerId", request.customerId()).param("key", key)
        .query(this::mapExistingHold).optional();
    if (existing.isPresent()) {
      metrics.counter("serv60.booking.holds", "outcome", "replayed").increment();
      if (existing.get().requestHash() != null && !existing.get().requestHash().equals(requestHash)) {
        throw new Conflict("Idempotency-Key was reused with a different request");
      }
      return existing.get().hold();
    }

    if (request.vehicleId() != null) {
      boolean owned = db.sql("select exists(select 1 from customer_vehicle where id=:id and customer_id=:customer)")
          .param("id", request.vehicleId()).param("customer", request.customerId()).query(Boolean.class).single();
      if (!owned) throw new IllegalArgumentException("Vehicle is not on this customer profile");
    }
    int complimentaryMinutes = complimentaryDuration(request.hubId(), request.serviceCode(), request.complimentaryServiceCode());
    var service = db.sql("select id, duration_minutes, required_parts from service_template where hub_id=:hubId and code=:code and active=true")
        .param("hubId", request.hubId()).param("code", request.serviceCode())
        .query((rs, n) -> new ServiceRow(rs.getObject("id", UUID.class), rs.getInt("duration_minutes"), rs.getString("required_parts")))
        .optional().orElseThrow(() -> new IllegalArgumentException("Unknown active service"));
    var id = UUID.randomUUID();
    // Match PostgreSQL precision so the initial and replayed responses agree.
    var expires = OffsetDateTime.now(clock).plus(holdDuration).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
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
    metrics.counter("serv60.booking.holds", "outcome", "created", "policy", policy).increment();
    return new BookingContracts.Hold(id, request.hubId(), request.customerId(), request.serviceCode(),
        request.startsAt(), request.endsAt(), VisitStatus.HELD.name(), policy, expires, request.complimentaryServiceCode());
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
        .orElseThrow(() -> new Conflict("No compatible resource is available"));
    // Recheck with a fresh READ COMMITTED snapshot after acquiring the resource lock.
    // A competing allocation may have committed while the selection query was running.
    boolean occupied = db.sql("""
        select exists (select 1 from resource_allocation where resource_id=:resource
          and status in ('HELD','CONFIRMED') and starts_at < :ends and ends_at > :starts)
        """).param("resource", resource).param("starts", startsAt).param("ends", endsAt)
        .query(Boolean.class).single();
    if (occupied) throw new Conflict("Resource was just reserved; please check availability again");
    db.sql("insert into resource_allocation(id,hold_id,resource_id,starts_at,ends_at,status) values (:id,:hold,:resource,:starts,:ends,'HELD')")
        .param("id", UUID.randomUUID()).param("hold", holdId).param("resource", resource)
        .param("starts", startsAt).param("ends", endsAt).update();
  }

  /** Only a malformed template is an internal error; stock and database failures keep their type. */
  private JsonNode parts(String rawParts) {
    try {
      return json.readTree(rawParts);
    } catch (tools.jackson.core.JacksonException error) {
      throw new IllegalStateException("Invalid service parts definition", error);
    }
  }

  private void recordPartsDemand(String rawParts, UUID holdId, UUID hubId) {
    for (JsonNode part : parts(rawParts)) {
      db.sql("insert into parts_demand(id,hold_id,hub_id,sku,quantity,status) values (:id,:hold,:hub,:sku,:quantity,'PENDING')")
          .param("id", UUID.randomUUID()).param("hold", holdId).param("hub", hubId)
          .param("sku", part.path("sku").asText()).param("quantity", part.path("quantity").asInt()).update();
    }
  }

  private void reserveParts(String rawParts, UUID holdId, UUID hubId) {
    for (JsonNode part : parts(rawParts)) {
      String sku = part.path("sku").asText(); int quantity = part.path("quantity").asInt();
      int updated = db.sql("update stock_balance set reserved_quantity=reserved_quantity+:quantity where hub_id=:hub and sku=:sku and usable_quantity-reserved_quantity>=:quantity")
          .param("quantity", quantity).param("hub", hubId).param("sku", sku).update();
      if (updated != 1) throw new Conflict("Required part is not ready: " + sku);
      db.sql("insert into stock_reservation(id,hold_id,hub_id,sku,quantity,status) values (:id,:hold,:hub,:sku,:quantity,'HELD')")
          .param("id", UUID.randomUUID()).param("hold", holdId).param("hub", hubId).param("sku", sku).param("quantity", quantity).update();
    }
  }

  /**
   * Confirms an unexpired hold. {@code actor} is recorded for audit, for example
   * {@code operator:<subject>}; an operator confirmation does not require payment.
   */
  @Transactional
  public BookingContracts.Hold confirm(UUID id, String actor) {
    return confirm(id, false, actor);
  }

  /**
   * Confirms a hold whose payment the provider has settled. The hold's resources stay allocated
   * until the expiry sweep releases them, so a settled hold past its display expiry is still safe
   * to confirm. Replays of an already confirmed hold return it unchanged.
   */
  @Transactional
  public BookingContracts.Hold confirmSettled(UUID id, String actor) {
    return confirm(id, true, actor);
  }

  private BookingContracts.Hold confirm(UUID id, boolean settled, String actor) {
    java.util.Objects.requireNonNull(actor, "actor");
    var hold = db.sql("select vh.*, st.code service_code from visit_hold vh join service_template st on st.id=vh.service_template_id where vh.id=:id for update")
        .param("id", id).query(this::mapHoldWithCode).optional()
        .orElseThrow(() -> new NotFound("Visit hold not found"));
    var status = VisitStatus.valueOf(hold.status());
    if (settled && status == VisitStatus.CONFIRMED) return hold;
    boolean expired = hold.expiresAt().isBefore(OffsetDateTime.now(clock));
    if (status != VisitStatus.HELD || (expired && !settled)) {
      throw new Conflict("Hold expired or no longer confirmable");
    }
    db.sql("update visit_hold set status='CONFIRMED', confirmed_at=:at, confirmed_by=:actor where id=:id")
        .param("at", OffsetDateTime.now(clock)).param("actor", actor).param("id", id).update();
    db.sql("insert into outbox_event(id,event_type,aggregate_id,payload) values (:event,'VisitConfirmed',:id,cast(:payload as jsonb))")
        .param("event", UUID.randomUUID()).param("id", id)
        .param("payload", json.writeValueAsString(java.util.Map.of("holdId", id, "confirmedBy", actor, "paid", settled)))
        .update();
    metrics.counter("serv60.booking.confirmations", "paid", Boolean.toString(settled)).increment();
    db.sql("update resource_allocation set status='CONFIRMED' where hold_id=:id").param("id", id).update();
    db.sql("update stock_reservation set status='CONFIRMED' where hold_id=:id").param("id", id).update();
    return new BookingContracts.Hold(id, hold.hubId(), hold.customerId(), hold.serviceCode(), hold.startsAt(), hold.endsAt(), VisitStatus.CONFIRMED.name(), hold.policy(), hold.expiresAt(), hold.complimentaryServiceCode());
  }

  @Transactional
  public BookingContracts.Hold cancel(UUID id) {
    var hold = db.sql("select vh.*, st.code service_code from visit_hold vh join service_template st on st.id=vh.service_template_id where vh.id=:id for update")
        .param("id", id).query(this::mapHoldWithCode).optional().orElseThrow();
    if (VisitStatus.valueOf(hold.status()).closed()) return hold;
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
    requestRefundIfPaid(id);
    metrics.counter("serv60.booking.cancellations").increment();
    return new BookingContracts.Hold(id, hold.hubId(), hold.customerId(), hold.serviceCode(), hold.startsAt(), hold.endsAt(), VisitStatus.CANCELLED.name(), hold.policy(), hold.expiresAt(), hold.complimentaryServiceCode());
  }

  /**
   * A cancelled visit that was paid needs its money back. Refund policy is a business decision, so
   * the platform never refunds on its own: it records RefundRequired for operations and counts it.
   */
  private void requestRefundIfPaid(UUID holdId) {
    var paid = db.sql("select id, amount_minor from payment_order where hold_id=:hold and status='PAID'")
        .param("hold", holdId)
        .query((rs, row) -> java.util.Map.entry(rs.getObject("id", UUID.class), rs.getLong("amount_minor")))
        .list();
    for (var order : paid) {
      db.sql("insert into outbox_event(id,event_type,aggregate_id,payload) values (:id,'RefundRequired',:aggregate,cast(:payload as jsonb))")
          .param("id", UUID.randomUUID()).param("aggregate", holdId)
          .param("payload", json.writeValueAsString(java.util.Map.of(
              "holdId", holdId, "paymentOrderId", order.getKey(), "amountMinor", order.getValue())))
          .update();
      metrics.counter("serv60.payment.refund.required").increment();
    }
  }

  /** Expires every due hold; used by tests and tools. The scheduled job uses bounded batches. */
  @Transactional
  public int expireDueHolds(OffsetDateTime now) {
    return expireDueHolds(now, Integer.MAX_VALUE);
  }

  /**
   * Expires at most {@code limit} due holds, oldest first. Holds locked by an in-flight
   * confirmation or payment settlement are skipped and retried on the next sweep.
   */
  @Transactional
  public int expireDueHolds(OffsetDateTime now, int limit) {
    var ids = db.sql("""
        select id from visit_hold where status='HELD' and expires_at <= :now
         order by expires_at, id limit :limit for update skip locked
        """)
        .param("now", now).param("limit", limit).query((rs, row) -> rs.getObject("id", UUID.class)).list();
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
    metrics.counter("serv60.booking.expirations").increment(ids.size());
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
