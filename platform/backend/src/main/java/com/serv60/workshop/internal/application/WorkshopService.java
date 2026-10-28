package com.serv60.workshop.internal.application;

import static com.serv60.workshop.api.WorkshopContracts.*;

import com.serv60.booking.api.BookingContracts.AppointmentContact;
import com.serv60.errors.Conflict;
import com.serv60.errors.NotFound;
import com.serv60.hubs.api.HubConfiguration;
import com.serv60.workshop.api.VisitStage;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Records what happens to a confirmed visit on the floor and what extra work the customer is
 * asked to approve. Stages are recorded strictly in order, each once; the customer reads progress
 * and decides proposals, operators (or the demo simulation) record stages and propose work.
 */
@Service
public class WorkshopService {
  /** Stale confirmed visits are never checked in by the simulation; a person decides those. */
  static final Duration AUTO_CHECK_IN_WINDOW = Duration.ofHours(3);
  private static final String STAGE_ORDER = VisitStage.sqlOrder();
  private static final String FLOOR_SQL =
      """
      select vh.id, vh.hub_id, vh.customer_id, vh.starts_at, vh.ends_at, vh.status,
             vh.complimentary_service_code, vh.appointment_contact, h.name hub_name,
             s.stage, s.checked_in_at, s.ready_at, s.handed_over_at, s.last_at,
             b.bay_code, coalesce(sc.pending,0) pending, coalesce(sc.approved_minor,0) approved_minor,
             inv.number invoice_number, inv.total_minor invoice_total
        from visit_hold vh
        join hub h on h.id=vh.hub_id
        left join visit_invoice inv on inv.hold_id=vh.id
        left join lateral (
          select (select e2.stage from visit_stage_event e2 where e2.hold_id=vh.id
                   order by array_position(%s, e2.stage) desc limit 1) stage,
                 max(case when e.stage='CHECKED_IN' then e.recorded_at end) checked_in_at,
                 max(case when e.stage='READY' then e.recorded_at end) ready_at,
                 max(case when e.stage='HANDED_OVER' then e.recorded_at end) handed_over_at,
                 max(e.recorded_at) last_at
            from visit_stage_event e where e.hold_id=vh.id) s on true
        left join lateral (
          select r.code bay_code from resource_allocation ra join resource r on r.id=ra.resource_id
           where ra.hold_id=vh.id and r.kind='EXPRESS_BAY' and ra.status in ('HELD','CONFIRMED')
           order by r.code limit 1) b on true
        left join lateral (
          select count(*) filter (where c.status='PROPOSED') pending,
                 coalesce(sum(c.total_minor) filter (where c.status='APPROVED'),0) approved_minor
            from scope_change c where c.hold_id=vh.id) sc on true
      """
          .formatted(STAGE_ORDER);

  private final JdbcClient db;
  private final Clock clock;
  private final ObjectMapper json;
  private final HubConfiguration hubs;
  private final ApplicationEventPublisher events;
  private final long servicePriceMinor;

  public WorkshopService(
      JdbcClient db,
      Clock clock,
      ObjectMapper json,
      HubConfiguration hubs,
      ApplicationEventPublisher events,
      // What an unpaid express service costs on the invoice; defaults to the booking price.
      @Value("${serv60.workshop.service-price-minor:${serv60.booking.express-price-minor:0}}")
          long servicePriceMinor) {
    if (servicePriceMinor < 0) throw new IllegalArgumentException("serv60.workshop.service-price-minor must not be negative");
    this.db = db;
    this.clock = clock;
    this.json = json;
    this.hubs = hubs;
    this.events = events;
    this.servicePriceMinor = servicePriceMinor;
  }

  /** A visit as the floor query returns it, before it is shaped for a customer or an operator. */
  record VisitRow(
      UUID id,
      UUID hub,
      UUID customer,
      OffsetDateTime startsAt,
      OffsetDateTime endsAt,
      String status,
      String option,
      AppointmentContact contact,
      String hubName,
      VisitStage stage,
      OffsetDateTime checkedInAt,
      OffsetDateTime readyAt,
      OffsetDateTime handedOverAt,
      OffsetDateTime lastAt,
      String bayCode,
      int pending,
      long approvedMinor,
      String invoiceNumber,
      Long invoiceTotal) {}

  /** A visit the demo simulation may move: where it is and since when. */
  public record SimulationCandidate(UUID hub, UUID id, VisitStage stage, Instant since) {}

  // ---- customer ----------------------------------------------------------------------------

  @Transactional(readOnly = true)
  public VisitProgress progress(UUID customer, UUID visitId) {
    return progressOf(requireOwned(customer, visitId));
  }

  @Transactional
  public VisitProgress decide(UUID customer, UUID visitId, UUID scopeId, String decision) {
    lockVisit(visitId);
    var visit = requireOwned(customer, visitId);
    var status =
        db.sql("select status from scope_change where id=:id and hold_id=:visit for update")
            .param("id", scopeId)
            .param("visit", visitId)
            .query(String.class)
            .optional()
            .orElseThrow(() -> new NotFound("Extra work proposal not found"));
    if (!"PROPOSED".equals(status)) {
      throw new Conflict("This proposal was already " + status.toLowerCase(Locale.ROOT));
    }
    var outcome = "APPROVE".equals(decision) ? "APPROVED" : "DECLINED";
    var now = OffsetDateTime.now(clock);
    db.sql("update scope_change set status=:status, decided_at=:now where id=:id")
        .param("status", outcome)
        .param("now", now)
        .param("id", scopeId)
        .update();
    emit("ScopeChangeDecided", visitId, Map.of("visitId", visitId, "scopeChangeId", scopeId,
        "status", outcome, "decidedAt", now.toString()));
    events.publishEvent(new FloorChanged(visit.hub(), visitId));
    return progressOf(visit);
  }

  /** The invoice of a handed-over visit; NotFound before handover or for someone else's visit. */
  @Transactional(readOnly = true)
  public Invoice invoice(UUID customer, UUID visitId) {
    var visit = requireOwned(customer, visitId);
    return db.sql("select * from visit_invoice where hold_id=:id")
        .param("id", visitId)
        .query((rs, n) -> invoiceOf(visit, rs))
        .optional()
        .orElseThrow(() -> new NotFound("No invoice yet: it is issued when the car is handed over"));
  }

  /** The customer's handed-over visits with their invoices, newest first. */
  @Transactional(readOnly = true)
  public List<ServiceRecord> serviceHistory(UUID customer, int limit) {
    if (limit < 1 || limit > 50) throw new IllegalArgumentException("limit must be between 1 and 50");
    return db.sql(
            """
            select inv.hold_id, inv.number, inv.issued_at, inv.total_minor, inv.due_minor, h.name hub_name,
                   vh.appointment_contact
              from visit_invoice inv
              join visit_hold vh on vh.id=inv.hold_id
              join hub h on h.id=inv.hub_id
             where inv.customer_id=:customer
             order by inv.issued_at desc, inv.id
             limit :limit
            """)
        .param("customer", customer)
        .param("limit", limit)
        .query((rs, n) -> {
          var contact = contact(rs.getString("appointment_contact"));
          return new ServiceRecord(
              rs.getObject("hold_id", UUID.class),
              rs.getString("number"),
              rs.getString("hub_name"),
              vehicleOf(contact),
              contact == null ? null : contact.registration(),
              rs.getObject("issued_at", OffsetDateTime.class),
              rs.getLong("total_minor"),
              rs.getLong("due_minor"));
        })
        .list();
  }

  // ---- operator ----------------------------------------------------------------------------

  /**
   * Today's confirmed visits at the hub, plus any car still on the floor from before today. Not a
   * read-only transaction: reading the hub's settings takes the shared hub lock.
   */
  @Transactional
  public List<FloorVisit> floor(UUID hub) {
    var zone = ZoneId.of(hubs.read(hub).timezone());
    var from = LocalDate.now(clock.withZone(zone)).atStartOfDay(zone).toOffsetDateTime();
    return db.sql(FLOOR_SQL + """
             where vh.hub_id=:hub and vh.status='CONFIRMED'
               and ((vh.starts_at >= :from and vh.starts_at < :to)
                    or (s.checked_in_at is not null and s.handed_over_at is null))
             order by vh.starts_at, vh.id
            """)
        .param("hub", hub)
        .param("from", from)
        .param("to", from.plusDays(1))
        .query(this::row)
        .list()
        .stream()
        .map(this::floorVisit)
        .toList();
  }

  @Transactional
  public FloorVisit checkIn(UUID hub, UUID visitId, String note, String recordedBy) {
    return recordStage(hub, visitId, VisitStage.CHECKED_IN, note, recordedBy);
  }

  @Transactional
  public FloorVisit recordStage(UUID hub, UUID visitId, VisitStage stage, String note, String recordedBy) {
    lockVisit(visitId);
    var visit = requireAtHub(hub, visitId);
    if (!"CONFIRMED".equals(visit.status())) {
      throw new Conflict("Only a confirmed visit can be on the floor");
    }
    var expected = VisitStage.after(visit.stage());
    if (expected.isEmpty()) throw new Conflict("This visit has been handed over");
    if (expected.get() != stage) {
      throw new Conflict("Record " + expected.get() + " before " + stage);
    }
    var now = OffsetDateTime.now(clock);
    db.sql(
            "insert into visit_stage_event(id,hold_id,stage,note,recorded_by,recorded_at)"
                + " values (:id,:visit,:stage,:note,:by,:now)")
        .param("id", UUID.randomUUID())
        .param("visit", visitId)
        .param("stage", stage.name())
        .param("note", blankToNull(note))
        .param("by", recordedBy)
        .param("now", now)
        .update();
    emit("VisitStageRecorded", visitId, Map.of("visitId", visitId, "hubId", hub,
        "stage", stage.name(), "recordedAt", now.toString(), "recordedBy", recordedBy));
    if (stage == VisitStage.HANDED_OVER) issueInvoice(visit, now);
    events.publishEvent(new FloorChanged(hub, visitId));
    return floorVisit(requireAtHub(hub, visitId));
  }

  @Transactional
  public ScopeChange propose(UUID hub, UUID visitId, ProposeScopeChange request, String proposedBy) {
    lockVisit(visitId);
    var visit = requireAtHub(hub, visitId);
    if (!"CONFIRMED".equals(visit.status())) {
      throw new Conflict("Extra work can only be proposed on a confirmed visit");
    }
    if (visit.stage() != null && !visit.stage().countsTowardTarget()) {
      throw new Conflict("Extra work cannot be proposed once the vehicle is ready");
    }
    long total = request.items().stream().mapToLong(ScopeItem::amountMinor).sum();
    var id = UUID.randomUUID();
    var now = OffsetDateTime.now(clock);
    db.sql(
            "insert into scope_change(id,hold_id,status,items,note,total_minor,currency,proposed_by,proposed_at)"
                + " values (:id,:visit,'PROPOSED',cast(:items as jsonb),:note,:total,'ZAR',:by,:now)")
        .param("id", id)
        .param("visit", visitId)
        .param("items", json.writeValueAsString(request.items()))
        .param("note", blankToNull(request.note()))
        .param("total", total)
        .param("by", proposedBy)
        .param("now", now)
        .update();
    emit("ScopeChangeProposed", visitId, Map.of("visitId", visitId, "hubId", hub,
        "scopeChangeId", id, "totalMinor", total, "proposedAt", now.toString()));
    events.publishEvent(new FloorChanged(hub, visitId));
    return new ScopeChange(id, visitId, "PROPOSED", List.copyOf(request.items()),
        blankToNull(request.note()), total, "ZAR", now, null);
  }

  // ---- demo simulation ---------------------------------------------------------------------

  /** Confirmed visits whose start has passed recently and that nobody has checked in. */
  @Transactional(readOnly = true)
  public List<SimulationCandidate> awaitingCheckIn(Instant now) {
    return db.sql(
            """
            select vh.hub_id, vh.id, vh.starts_at from visit_hold vh
             where vh.status='CONFIRMED' and vh.starts_at <= :now and vh.starts_at > :earliest
               and not exists (select 1 from visit_stage_event e where e.hold_id=vh.id)
             order by vh.starts_at
            """)
        .param("now", now.atOffset(ZoneOffset.UTC))
        .param("earliest", now.minus(AUTO_CHECK_IN_WINDOW).atOffset(ZoneOffset.UTC))
        .query((rs, n) -> new SimulationCandidate(rs.getObject("hub_id", UUID.class),
            rs.getObject("id", UUID.class), null,
            rs.getObject("starts_at", OffsetDateTime.class).toInstant()))
        .list();
  }

  /** Visits on the floor before READY with no proposal waiting on the customer. */
  @Transactional(readOnly = true)
  public List<SimulationCandidate> onTheFloor() {
    return db.sql(FLOOR_SQL + """
             where vh.status='CONFIRMED' and s.checked_in_at is not null and s.ready_at is null
               and coalesce(sc.pending,0)=0
             order by s.last_at
            """)
        .query(this::row)
        .list()
        .stream()
        .map(row -> new SimulationCandidate(row.hub(), row.id(), row.stage(), row.lastAt().toInstant()))
        .toList();
  }

  // ---- invoicing ---------------------------------------------------------------------------

  /**
   * Snapshots the visit's charges: the service (paid if a payment order settled it in the app,
   * otherwise due at the counter), the complimentary option at no charge, and each approved extra.
   */
  private void issueInvoice(VisitRow visit, OffsetDateTime now) {
    var lines = new ArrayList<InvoiceLine>();
    var paid = db.sql("select amount_minor from payment_order where hold_id=:id and status='PAID'"
            + " order by updated_at desc limit 1")
        .param("id", visit.id())
        .query(Long.class)
        .optional();
    lines.add(new InvoiceLine("Express vehicle service", paid.orElse(servicePriceMinor), paid.isPresent() ? "PAID" : "DUE"));
    if (visit.option() != null) {
      lines.add(new InvoiceLine(optionName(visit.option()) + " · complimentary", 0, "INCLUDED"));
    }
    db.sql("select items from scope_change where hold_id=:id and status='APPROVED' order by proposed_at, id")
        .param("id", visit.id())
        .query(String.class)
        .list()
        .forEach(items -> json.readValue(items, new TypeReference<List<ScopeItem>>() {})
            .forEach(item -> lines.add(new InvoiceLine(item.description(), item.amountMinor(), "DUE"))));
    long total = lines.stream().mapToLong(InvoiceLine::amountMinor).sum();
    long paidMinor = lines.stream().filter(line -> line.status().equals("PAID")).mapToLong(InvoiceLine::amountMinor).sum();
    var zone = ZoneId.of(hubs.read(visit.hub()).timezone());
    var number = "S60-" + now.atZoneSameInstant(zone).toLocalDate().toString().replace("-", "")
        + "-" + visit.id().toString().substring(0, 6).toUpperCase(Locale.ROOT);
    db.sql(
            "insert into visit_invoice(id,hold_id,customer_id,hub_id,number,lines,total_minor,paid_minor,due_minor,currency,issued_at)"
                + " values (:id,:hold,:customer,:hub,:number,cast(:lines as jsonb),:total,:paid,:due,'ZAR',:now)")
        .param("id", UUID.randomUUID())
        .param("hold", visit.id())
        .param("customer", visit.customer())
        .param("hub", visit.hub())
        .param("number", number)
        .param("lines", json.writeValueAsString(lines))
        .param("total", total)
        .param("paid", paidMinor)
        .param("due", total - paidMinor)
        .param("now", now)
        .update();
    emit("VisitInvoiced", visit.id(), Map.of("visitId", visit.id(), "hubId", visit.hub(),
        "invoiceNumber", number, "totalMinor", total, "dueMinor", total - paidMinor));
  }

  private Invoice invoiceOf(VisitRow visit, ResultSet rs) throws SQLException {
    List<InvoiceLine> lines = json.readValue(rs.getString("lines"), new TypeReference<List<InvoiceLine>>() {});
    var contact = visit.contact();
    return new Invoice(
        visit.id(),
        rs.getString("number"),
        visit.hubName(),
        contact == null ? null : contact.customerName(),
        vehicleOf(contact),
        contact == null ? null : contact.registration(),
        visit.startsAt(),
        rs.getObject("issued_at", OffsetDateTime.class),
        lines,
        rs.getLong("total_minor"),
        rs.getLong("paid_minor"),
        rs.getLong("due_minor"),
        rs.getString("currency"));
  }

  static String optionName(String code) {
    return switch (code) {
      case "BARBER" -> "Barber";
      case "MASSAGE" -> "Massage";
      case "PRIVATE_WORK" -> "Private workspace";
      default -> code;
    };
  }

  private AppointmentContact contact(String value) {
    return value == null ? null : json.readValue(value, AppointmentContact.class);
  }

  private static String vehicleOf(AppointmentContact contact) {
    return contact == null ? "" : ((Objects.toString(contact.vehicleMake(), "") + " "
        + Objects.toString(contact.vehicleModel(), "")).trim());
  }

  // ---- shaping -----------------------------------------------------------------------------

  private VisitProgress progressOf(VisitRow visit) {
    var events =
        db.sql("select stage, recorded_at, note from visit_stage_event where hold_id=:id"
                + " order by array_position(" + STAGE_ORDER + ", stage)")
            .param("id", visit.id())
            .query((rs, n) -> new StageEvent(rs.getString("stage"),
                rs.getObject("recorded_at", OffsetDateTime.class), rs.getString("note")))
            .list();
    var changes =
        db.sql("select * from scope_change where hold_id=:id order by proposed_at, id")
            .param("id", visit.id())
            .query(this::scopeChange)
            .list();
    return new VisitProgress(
        visit.id(),
        visit.hubName(),
        visit.startsAt(),
        visit.option(),
        visit.stage() == null ? null : visit.stage().name(),
        visit.checkedInAt(),
        visit.readyAt(),
        visit.handedOverAt(),
        TARGET_MINUTES,
        elapsedMinutes(visit),
        events,
        changes,
        visit.checkedInAt() == null ? null : voucherCode(visit.id()));
  }

  private FloorVisit floorVisit(VisitRow visit) {
    var contact = visit.contact();
    var vehicle = contact == null ? "" : ((Objects.toString(contact.vehicleMake(), "") + " "
        + Objects.toString(contact.vehicleModel(), "")).trim());
    return new FloorVisit(
        visit.id(),
        visit.startsAt(),
        visit.endsAt(),
        visit.status(),
        visit.stage() == null ? null : visit.stage().name(),
        visit.checkedInAt(),
        visit.readyAt(),
        visit.handedOverAt(),
        elapsedMinutes(visit),
        TARGET_MINUTES,
        contact == null || contact.customerName() == null ? "Walk-in" : contact.customerName(),
        vehicle,
        contact == null ? null : contact.registration(),
        visit.option(),
        visit.bayCode(),
        visit.pending(),
        visit.approvedMinor(),
        VisitStage.after(visit.stage()).map(next -> List.of(next.name())).orElse(List.of()),
        visit.invoiceNumber(),
        visit.invoiceTotal());
  }

  private Integer elapsedMinutes(VisitRow visit) {
    if (visit.checkedInAt() == null) return null;
    var end = visit.readyAt() != null ? visit.readyAt().toInstant() : clock.instant();
    return (int) Math.max(0, Duration.between(visit.checkedInAt().toInstant(), end).toMinutes());
  }

  static String voucherCode(UUID visitId) {
    return "COFFEE-" + visitId.toString().substring(0, 4).toUpperCase(Locale.ROOT);
  }

  private ScopeChange scopeChange(ResultSet rs, int row) throws SQLException {
    List<ScopeItem> items = json.readValue(rs.getString("items"), new TypeReference<List<ScopeItem>>() {});
    return new ScopeChange(
        rs.getObject("id", UUID.class),
        rs.getObject("hold_id", UUID.class),
        rs.getString("status"),
        items,
        rs.getString("note"),
        rs.getLong("total_minor"),
        rs.getString("currency"),
        rs.getObject("proposed_at", OffsetDateTime.class),
        rs.getObject("decided_at", OffsetDateTime.class));
  }

  private VisitRow row(ResultSet rs, int n) throws SQLException {
    var contact = rs.getString("appointment_contact");
    var stage = rs.getString("stage");
    return new VisitRow(
        rs.getObject("id", UUID.class),
        rs.getObject("hub_id", UUID.class),
        rs.getObject("customer_id", UUID.class),
        rs.getObject("starts_at", OffsetDateTime.class),
        rs.getObject("ends_at", OffsetDateTime.class),
        rs.getString("status"),
        rs.getString("complimentary_service_code"),
        contact == null ? null : json.readValue(contact, AppointmentContact.class),
        rs.getString("hub_name"),
        stage == null ? null : VisitStage.valueOf(stage),
        rs.getObject("checked_in_at", OffsetDateTime.class),
        rs.getObject("ready_at", OffsetDateTime.class),
        rs.getObject("handed_over_at", OffsetDateTime.class),
        rs.getObject("last_at", OffsetDateTime.class),
        rs.getString("bay_code"),
        rs.getInt("pending"),
        rs.getLong("approved_minor"),
        rs.getString("invoice_number"),
        rs.getObject("invoice_total", Long.class));
  }

  private VisitRow requireOwned(UUID customer, UUID visitId) {
    return db.sql(FLOOR_SQL + " where vh.id=:id and vh.customer_id=:customer")
        .param("id", visitId)
        .param("customer", customer)
        .query(this::row)
        .optional()
        .orElseThrow(() -> new NotFound("Appointment not found"));
  }

  private VisitRow requireAtHub(UUID hub, UUID visitId) {
    return db.sql(FLOOR_SQL + " where vh.id=:id and vh.hub_id=:hub")
        .param("id", visitId)
        .param("hub", hub)
        .query(this::row)
        .optional()
        .orElseThrow(() -> new NotFound("Visit not found at this hub"));
  }

  /** Serialises stage and proposal writes for one visit without blocking the rest of the floor. */
  private void lockVisit(UUID visitId) {
    db.sql("select id from visit_hold where id=:id for update").param("id", visitId).query(UUID.class).optional();
  }

  private void emit(String type, UUID aggregate, Map<String, Object> payload) {
    db.sql(
            "insert into outbox_event(id,event_type,aggregate_id,payload)"
                + " values (:id,:type,:aggregate,cast(:payload as jsonb))")
        .param("id", UUID.randomUUID())
        .param("type", type)
        .param("aggregate", aggregate)
        .param("payload", json.writeValueAsString(payload))
        .update();
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }
}
