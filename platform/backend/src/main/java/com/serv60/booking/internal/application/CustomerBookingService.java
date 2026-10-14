package com.serv60.booking.internal.application;

import com.serv60.booking.api.BookingContracts;
import com.serv60.booking.api.CustomerBookingContracts.*;
import com.serv60.booking.internal.domain.VisitChangePolicy;
import com.serv60.hubs.api.HubDiscovery;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Owns customer appointment queries and atomic changes; delegates resource allocation to
 * BookingService.
 */
@Service
public class CustomerBookingService implements com.serv60.booking.api.CustomerBookings {
  private final JdbcClient db;
  private final BookingService bookings;
  private final HubDiscovery hubs;
  private final Clock clock;
  private final com.serv60.hubs.api.HubSchedule schedule;
  private final ObjectMapper json;
  private final com.serv60.payment.api.PaymentOrders payments;
  private final long priceMinor;
  private static final UUID NO_HOLD = new UUID(0, 0);
  private static final String APPOINTMENT =
      """
      select vh.*, h.name hub_name, h.timezone, st.code service_code from visit_hold vh
        join hub h on h.id=vh.hub_id join service_template st on st.id=vh.service_template_id
      """;

  public CustomerBookingService(
      JdbcClient db,
      BookingService bookings,
      HubDiscovery hubs,
      com.serv60.hubs.api.HubSchedule schedule,
      Clock clock,
      ObjectMapper json,
      com.serv60.payment.api.PaymentOrders payments,
      @org.springframework.beans.factory.annotation.Value("${serv60.booking.express-price-minor:0}")
          long priceMinor) {
    this.db = db;
    this.bookings = bookings;
    this.hubs = hubs;
    this.schedule = schedule;
    this.clock = clock;
    this.json = json;
    this.payments = payments;
    this.priceMinor = priceMinor;
  }

  /** Lists bounded upcoming appointments for the authenticated customer with keyset pagination. */
  public Appointments upcoming(UUID customer, int limit, OffsetDateTime after, UUID afterId) {
    if (limit < 1 || limit > 50 || (after == null) != (afterId == null))
      throw new IllegalArgumentException("Invalid booking page");
    var now = OffsetDateTime.now(clock);
    var rows =
        db.sql(
                APPOINTMENT
                    + """
                    where vh.customer_id=:customer and vh.starts_at>=:now
                      and (vh.status='CONFIRMED' or (vh.status='HELD' and vh.expires_at>:now))
                      and (vh.starts_at, vh.id) > (:after, :afterId)
                    order by vh.starts_at, vh.id limit :limit
                    """)
            .param("customer", customer)
            .param("now", now)
            .param("after", after == null ? now.minusNanos(1000) : after)
            .param("afterId", afterId == null ? NO_HOLD : afterId)
            .param("limit", limit + 1)
            .query(this::map)
            .list();
    boolean more = rows.size() > limit;
    var items = List.copyOf(rows.subList(0, Math.min(rows.size(), limit)));
    var last = more ? items.getLast() : null;
    return new Appointments(
        items, last == null ? null : last.startsAt(), last == null ? null : last.id());
  }

  /** Returns an appointment only when owned by this customer. */
  public Appointment require(UUID customer, UUID id) {
    return db.sql(APPOINTMENT + " where vh.id=:id and vh.customer_id=:customer")
        .param("id", id)
        .param("customer", customer)
        .query(this::map)
        .optional()
        .orElseThrow(() -> new IllegalArgumentException("Appointment not found"));
  }

  /** Searches actual bay and complimentary capacity within one published hub day. */
  public List<BookingContracts.Availability> slots(
      UUID customer, UUID hubId, LocalDate date, String option, UUID replacing) {
    var hub = hubs.require(hubId);
    var today = LocalDate.now(clock.withZone(ZoneId.of(hub.timezone())));
    if (date == null || date.isBefore(today) || date.isAfter(today.plusDays(90))) {
      throw new IllegalArgumentException("Choose a date within the next 90 days");
    }
    if (option != null && !hub.complimentaryServices().contains(option)) {
      throw new IllegalArgumentException(
          "This complimentary service is not available at the selected hub");
    }
    if (replacing != null) {
      var old = require(customer, replacing);
      if (!old.hubId().equals(hubId) || !old.modifiable())
        throw new IllegalStateException("This appointment cannot be changed");
    }
    var hours = schedule.day(hubId, date);
    if (hours.closed()) return List.of();
    var starts = date.atTime(hours.opensAt()).atZone(ZoneId.of(hub.timezone())).toOffsetDateTime();
    var ends =
        date.atTime(hours.closesAt())
            .atZone(ZoneId.of(hub.timezone()))
            .toOffsetDateTime()
            .minusHours(1);
    int optionMinutes = bookings.complimentaryDuration(hubId, "EXPRESS_OIL", option);
    var kind =
        switch (option == null ? "" : option) {
          case "BARBER" -> "BARBER_CHAIR";
          case "MASSAGE" -> "MASSAGE_ROOM";
          default -> "WORKSPACE";
        };
    var readiness = readinessPolicy(hubId);
    return db.sql(
            """
            select candidate from generate_series(cast(:starts as timestamptz),cast(:ends as timestamptz),interval '1 hour') candidate
              where candidate>:now and (:changing=false or candidate>=:changeFrom) and exists (select 1 from resource r
                where r.hub_id=:hub and r.active=true and r.kind='EXPRESS_BAY'
                  and not exists (select 1 from resource_allocation a where a.resource_id=r.id
                    and a.hold_id<>:exclude and a.status in ('HELD','CONFIRMED')
                    and a.starts_at<candidate+interval '1 hour' and a.ends_at>candidate))
              and (:minutes=0 or exists (select 1 from resource r
                where r.hub_id=:hub and r.active=true and r.kind=:kind
                  and not exists (select 1 from resource_allocation a where a.resource_id=r.id
                    and a.hold_id<>:exclude and a.status in ('HELD','CONFIRMED')
                    and a.starts_at<candidate+make_interval(mins=>:minutes) and a.ends_at>candidate)))
            order by candidate limit 24
            """)
        .param("starts", starts)
        .param("ends", ends)
        .param("now", OffsetDateTime.now(clock))
        .param("hub", hubId)
        .param("changing", replacing != null)
        .param("changeFrom", OffsetDateTime.now(clock).plusHours(48))
        .param("exclude", replacing == null ? NO_HOLD : replacing)
        .param("minutes", optionMinutes)
        .param("kind", kind)
        .query(
            (rs, row) -> {
              var at =
                  rs.getObject("candidate", OffsetDateTime.class)
                      .atZoneSameInstant(ZoneId.of(hub.timezone()))
                      .toOffsetDateTime();
              return new BookingContracts.Availability(
                  hubId, "EXPRESS_OIL", at, at.plusHours(1), readiness);
            })
        .list();
  }

  /**
   * Reserves one vehicle service and the selected complimentary option, safely replaying retries.
   */
  @Transactional
  public BookingContracts.Hold create(UUID customer, NewVisit visit, String key) {
    validateKey(key);
    // Serializes retries before checking capacity; BookingService verifies the canonical request
    // hash.
    db.sql("select pg_advisory_xact_lock(hashtextextended(:key,0))")
        .param("key", customer + ":" + key)
        .query((rs, row) -> true)
        .single();
    var existingPolicy =
        db.sql("select policy from visit_hold where customer_id=:customer and idempotency_key=:key")
            .param("customer", customer)
            .param("key", key)
            .query(String.class)
            .optional();
    var policy =
        existingPolicy.orElseGet(
            () ->
                requireSlot(
                        customer,
                        visit.hubId(),
                        visit.startsAt(),
                        visit.complimentaryServiceCode(),
                        null)
                    .readinessPolicy());
    var vehicleId = requireOwnedVehicle(customer, visit.vehicleId());
    return bookings.createHold(
        new BookingContracts.CreateHold(
            visit.hubId(),
            customer,
            vehicleId,
            "EXPRESS_OIL",
            visit.startsAt(),
            visit.startsAt().plusHours(1),
            policy,
            visit.complimentaryServiceCode(),
            visit.contact()),
        key);
  }

  private UUID requireOwnedVehicle(UUID customer, UUID vehicleId) {
    if (vehicleId == null) return null;
    var owned =
        db.sql("select id from customer_vehicle where id=:id and customer_id=:customer")
            .param("id", vehicleId)
            .param("customer", customer)
            .query(UUID.class)
            .optional();
    if (owned.isEmpty()) {
      throw new IllegalArgumentException("Vehicle is not on this customer profile");
    }
    return vehicleId;
  }

  /**
   * Changes a confirmed appointment without releasing its original capacity unless the entire
   * change commits.
   */
  @Transactional
  public Appointment change(UUID customer, UUID id, ChangeVisit request, String key) {
    validateKey(key);
    var old =
        db.sql(APPOINTMENT + " where vh.id=:id and vh.customer_id=:customer for update of vh")
            .param("id", id)
            .param("customer", customer)
            .query(this::map)
            .optional()
            .orElseThrow(() -> new IllegalArgumentException("Appointment not found"));
    var hash = hash(json.writeValueAsString(request));
    var receipt =
        db.sql(
                "select request_hash,response from appointment_change_receipt where hold_id=:id and"
                    + " customer_id=:customer and idempotency_key=:key")
            .param("id", id)
            .param("customer", customer)
            .param("key", key)
            .query((rs, row) -> Map.entry(rs.getString("request_hash"), rs.getString("response")))
            .optional();
    if (receipt.isPresent()) {
      if (!hash.equals(receipt.get().getKey()))
        throw new IllegalStateException("Idempotency-Key was reused with a different change");
      return json.readValue(receipt.get().getValue(), Appointment.class);
    }
    if (old.revision() != request.revision())
      throw new IllegalStateException("This booking changed. Refresh it and try again");
    VisitChangePolicy.requireChange(
        old.status(), old.startsAt().toInstant(), request.startsAt().toInstant(), clock.instant());
    if (!"EXPRESS_OIL".equals(old.serviceCode()))
      throw new IllegalArgumentException("Contact the hub to change this legacy appointment");
    requireSlot(customer, old.hubId(), request.startsAt(), request.complimentaryServiceCode(), id);
    db.sql(
            "update resource_allocation set status='RELEASED' where hold_id=:id and"
                + " status='CONFIRMED'")
        .param("id", id)
        .update();
    bookings.allocateResource(
        id, old.hubId(), "EXPRESS_OIL", request.startsAt(), request.startsAt().plusHours(1));
    int minutes =
        bookings.complimentaryDuration(
            old.hubId(), "EXPRESS_OIL", request.complimentaryServiceCode());
    if (minutes > 0)
      bookings.allocateResource(
          id,
          old.hubId(),
          request.complimentaryServiceCode(),
          request.startsAt(),
          request.startsAt().plusMinutes(minutes));
    db.sql("update resource_allocation set status='CONFIRMED' where hold_id=:id and status='HELD'")
        .param("id", id)
        .update();
    db.sql(
            "update visit_hold set"
                + " starts_at=:starts,ends_at=:ends,complimentary_service_code=:option,revision=revision+1"
                + " where id=:id")
        .param("starts", request.startsAt())
        .param("ends", request.startsAt().plusHours(1))
        .param("option", request.complimentaryServiceCode())
        .param("id", id)
        .update();
    var changed = require(customer, id);
    db.sql(
            "insert into"
                + " appointment_change_receipt(hold_id,customer_id,idempotency_key,request_hash,response)"
                + " values (:id,:customer,:key,:hash,cast(:response as jsonb))")
        .param("id", id)
        .param("customer", customer)
        .param("key", key)
        .param("hash", hash)
        .param("response", json.writeValueAsString(changed))
        .update();
    db.sql(
            "insert into outbox_event(id,event_type,aggregate_id,payload) values"
                + " (:event,'VisitRescheduled',:id,cast(:payload as jsonb))")
        .param("event", UUID.randomUUID())
        .param("id", id)
        .param(
            "payload",
            json.writeValueAsString(
                Map.of(
                    "holdId", id, "startsAt", request.startsAt(), "revision", changed.revision())))
        .update();
    return changed;
  }

  /** Creates a payment using the server price and the authenticated customer's hold. */
  @Transactional
  public com.serv60.payment.api.PaymentContracts.PaymentOrder payment(UUID customer, UUID id) {
    var appointment = require(customer, id);
    if (priceMinor <= 0)
      throw new IllegalStateException(
          "Online payment is not configured yet. Please contact the hub");
    if (!appointment.status().equals("HELD")
        || !appointment.expiresAt().isAfter(OffsetDateTime.now(clock)))
      throw new IllegalStateException("Only an active reservation needs payment");
    db.sql("select pg_advisory_xact_lock(hashtextextended(:key,0))")
        .param("key", "customer-payment-" + id)
        .query((rs, row) -> true)
        .single();
    return payments.create(
        new com.serv60.payment.api.PaymentContracts.CreatePayment(
            customer, id, priceMinor, "ZAR", "YOCO"),
        "customer-visit-" + id);
  }

  /** Confirms only an owned hold with an authoritative paid order. */
  @Transactional
  public Appointment confirmPaid(UUID customer, UUID id) {
    var appointment = require(customer, id);
    if (appointment.status().equals("CONFIRMED")) return appointment;
    boolean paid =
        db.sql(
                "select exists(select 1 from payment_order where hold_id=:hold and"
                    + " customer_id=:customer and status='PAID' and amount_minor=:amount and"
                    + " currency='ZAR')")
            .param("hold", id)
            .param("customer", customer)
            .param("amount", priceMinor)
            .query(Boolean.class)
            .single();
    if (!paid) throw new IllegalStateException("Complete payment before confirming your visit");
    if (!"HELD".equals(appointment.status()))
      throw new IllegalStateException(
          "Your payment arrived after this reservation was released. The hub will contact you");
    bookings.confirmSettled(id);
    return require(customer, id);
  }

  /** Cancels an owned hold or confirmed visit under the customer change/cancel notice policy. */
  @Transactional
  public Appointment cancel(UUID customer, UUID id) {
    var appointment =
        db.sql(APPOINTMENT + " where vh.id=:id and vh.customer_id=:customer for update of vh")
            .param("id", id)
            .param("customer", customer)
            .query(this::map)
            .optional()
            .orElseThrow(() -> new IllegalArgumentException("Appointment not found"));
    if ("CANCELLED".equals(appointment.status()) || "EXPIRED".equals(appointment.status())) {
      return appointment;
    }
    VisitChangePolicy.requireCancel(
        appointment.status(), appointment.startsAt().toInstant(), clock.instant());
    bookings.cancel(id);
    return require(customer, id);
  }

  private BookingContracts.Availability requireSlot(
      UUID customer, UUID hub, OffsetDateTime start, String option, UUID replacing) {
    var date = start.atZoneSameInstant(ZoneId.of(hubs.require(hub).timezone())).toLocalDate();
    return slots(customer, hub, date, option, replacing).stream()
        .filter(slot -> slot.startsAt().isEqual(start))
        .findFirst()
        .orElseThrow(
            () ->
                new IllegalStateException("That time is no longer available. Choose another slot"));
  }

  private String readinessPolicy(UUID hub) {
    // Evaluate every SKU independently: surplus oil cannot compensate for a missing filter.
    return db.sql(
            """
            select case when exists (
              select 1 from service_template st cross join lateral jsonb_array_elements(st.required_parts) part
              left join stock_balance sb on sb.hub_id=st.hub_id and sb.sku=part->>'sku'
              where st.hub_id=:hub and st.code='EXPRESS_OIL' and st.active=true
                and coalesce(sb.usable_quantity-sb.reserved_quantity,0)<(part->>'quantity')::int
            ) then 'CONDITIONAL_PROCUREMENT' else 'READY_STOCK' end
            """)
        .param("hub", hub)
        .query(String.class)
        .single();
  }

  private Appointment map(ResultSet rs, int row) throws SQLException {
    var starts = rs.getObject("starts_at", OffsetDateTime.class);
    var status = rs.getString("status");
    var contact = rs.getString("appointment_contact");
    return new Appointment(
        rs.getObject("id", UUID.class),
        rs.getObject("hub_id", UUID.class),
        rs.getString("hub_name"),
        rs.getString("timezone"),
        rs.getString("service_code"),
        starts,
        rs.getObject("ends_at", OffsetDateTime.class),
        status,
        rs.getString("complimentary_service_code"),
        contact == null ? null : json.readValue(contact, BookingContracts.AppointmentContact.class),
        rs.getLong("revision"),
        starts.minusHours(48),
        "EXPRESS_OIL".equals(rs.getString("service_code"))
            && VisitChangePolicy.canChange(status, starts.toInstant(), clock.instant()),
        "EXPRESS_OIL".equals(rs.getString("service_code"))
            && VisitChangePolicy.canCancel(status, starts.toInstant(), clock.instant()),
        rs.getObject("expires_at", OffsetDateTime.class));
  }

  private static void validateKey(String key) {
    if (key == null || key.isBlank() || key.length() > 160)
      throw new IllegalArgumentException("Idempotency-Key must contain 1 to 160 characters");
  }

  private static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is unavailable", error);
    }
  }
}
