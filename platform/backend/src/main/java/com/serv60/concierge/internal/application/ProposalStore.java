package com.serv60.concierge.internal.application;

import com.serv60.booking.api.CustomerBookingContracts.*;
import com.serv60.booking.api.CustomerBookings;
import com.serv60.concierge.api.AssistantUnavailable;
import com.serv60.concierge.api.ConciergeContracts.Proposal;
import com.serv60.hubs.api.HubContracts.Hub;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Stores immutable proposals; only a scoped customer confirmation can execute them. */
@Service
public class ProposalStore {
  private final JdbcClient db;
  private final ObjectMapper json;
  private final Clock clock;
  private final CustomerBookings bookings;

  public ProposalStore(JdbcClient db, ObjectMapper json, Clock clock, CustomerBookings bookings) {
    this.db = db;
    this.json = json;
    this.clock = clock;
    this.bookings = bookings;
  }

  /** Limits model requests to 30 per customer per UTC hour across all instances. */
  public void consumeQuota(UUID customer) {
    // Bounded retention work on active traffic; expired proposals are never executable.
    db.sql(
            "delete from assistant_usage where (customer_id,window_start) in (select"
                + " customer_id,window_start from assistant_usage where window_start < :before"
                + " limit 100)")
        .param("before", OffsetDateTime.now(clock).minusDays(2))
        .update();
    db.sql(
            "delete from assistant_proposal where id in (select id from assistant_proposal where"
                + " expires_at < :before limit 100)")
        .param("before", OffsetDateTime.now(clock).minusDays(30))
        .update();
    int accepted =
        db.sql(
                """
                insert into assistant_usage(customer_id,window_start,requests) values (:customer,date_trunc('hour',cast(:now as timestamptz)),1)
                on conflict(customer_id,window_start) do update set requests=assistant_usage.requests+1 where assistant_usage.requests<30
                """)
            .param("customer", customer)
            .param("now", OffsetDateTime.now(clock))
            .update();
    if (accepted != 1)
      throw new AssistantUnavailable(
          "You have reached the assistant limit for this hour. Please book manually or try later");
  }

  /** Prepares a reviewable booking without reserving any capacity. */
  public Proposal booking(UUID customer, Hub hub, NewVisit visit) {
    return save(
        customer,
        hub,
        "BOOK",
        null,
        visit,
        visit.startsAt(),
        visit.complimentaryServiceCode(),
        visit.contact());
  }

  /** Prepares a change against the current appointment revision. */
  public Proposal change(UUID customer, Hub hub, Appointment old, ChangeVisit change) {
    return save(
        customer,
        hub,
        "CHANGE",
        old.id(),
        change,
        change.startsAt(),
        change.complimentaryServiceCode(),
        old.contact());
  }

  private Proposal save(
      UUID customer,
      Hub hub,
      String kind,
      UUID appointment,
      Object payload,
      OffsetDateTime starts,
      String option,
      com.serv60.booking.api.BookingContracts.AppointmentContact contact) {
    var id = UUID.randomUUID();
    var expires =
        OffsetDateTime.now(clock).plusMinutes(10).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    db.sql(
            "insert into"
                + " assistant_proposal(id,customer_id,hub_id,kind,appointment_id,payload,expires_at)"
                + " values (:id,:customer,:hub,:kind,:appointment,cast(:payload as"
                + " jsonb),:expires)")
        .param("id", id)
        .param("customer", customer)
        .param("hub", hub.id())
        .param("kind", kind)
        .param("appointment", appointment)
        .param("payload", json.writeValueAsString(payload))
        .param("expires", expires)
        .update();
    return new Proposal(
        id,
        kind,
        hub.id(),
        hub.name(),
        appointment,
        starts,
        starts.plusHours(1),
        option,
        contact,
        expires);
  }

  /**
   * Confirms a stored proposal exactly once and returns its authoritative booking state on retries.
   */
  @Transactional
  public Appointment confirm(UUID customer, UUID proposalId) {
    var row =
        db.sql("select * from assistant_proposal where id=:id and customer_id=:customer for update")
            .param("id", proposalId)
            .param("customer", customer)
            .query(
                (rs, n) ->
                    new Stored(
                        rs.getString("kind"),
                        rs.getObject("appointment_id", UUID.class),
                        rs.getString("payload"),
                        rs.getObject("expires_at", OffsetDateTime.class),
                        rs.getObject("executed_hold_id", UUID.class)))
            .optional()
            .orElseThrow(() -> new IllegalArgumentException("Proposal not found"));
    if (row.executed() != null) return bookings.require(customer, row.executed());
    if (!row.expires().isAfter(OffsetDateTime.now(clock)))
      throw new IllegalStateException("This proposal expired. Ask for a new one");
    UUID id;
    if (row.kind().equals("BOOK")) {
      id =
          bookings
              .create(
                  customer,
                  json.readValue(row.payload(), NewVisit.class),
                  "assistant-" + proposalId)
              .id();
    } else {
      id =
          bookings
              .change(
                  customer,
                  row.appointment(),
                  json.readValue(row.payload(), ChangeVisit.class),
                  "assistant-" + proposalId)
              .id();
    }
    db.sql("update assistant_proposal set executed_hold_id=:hold where id=:id")
        .param("hold", id)
        .param("id", proposalId)
        .update();
    return bookings.require(customer, id);
  }

  private record Stored(
      String kind, UUID appointment, String payload, OffsetDateTime expires, UUID executed) {}
}
