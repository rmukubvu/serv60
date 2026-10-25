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
             b.bay_code, coalesce(sc.pending,0) pending, coalesce(sc.approved_minor,0) approved_minor
        from visit_hold vh
        join hub h on h.id=vh.hub_id
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

  public WorkshopService(
      JdbcClient db, Clock clock, ObjectMapper json, HubConfiguration hubs, ApplicationEventPublisher events) {
    this.db = db;
    this.clock = clock;
    this.json = json;
    this.hubs = hubs;
    this.events = events;
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
      long approvedMinor) {}

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
        VisitStage.after(visit.stage()).map(next -> List.of(next.name())).orElse(List.of()));
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
        rs.getLong("approved_minor"));
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
