package com.serv60.roadside.internal.application;

import com.serv60.errors.Conflict;
import com.serv60.errors.NotFound;
import com.serv60.roadside.api.IncidentStatus;
import com.serv60.roadside.api.RoadsideContracts;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import java.util.Map;

@Service
public class RoadsideService {
  private final JdbcClient db;
  private final Clock clock;
  private final ObjectMapper json;

  public RoadsideService(JdbcClient db, Clock clock, ObjectMapper json) {
    this.json = json;
    this.db = db;
    this.clock = clock;
  }

  @Transactional
  public RoadsideContracts.Incident create(RoadsideContracts.CreateIncident request, String key) {
    if (key == null || key.isBlank()) throw new IllegalArgumentException("Idempotency-Key is required");
    if (request.customerId() == null) throw new IllegalArgumentException("customerId is required");
    if (request.location() == null || request.location().isBlank()) throw new IllegalArgumentException("location is required");
    if (request.description() == null || request.description().isBlank()) throw new IllegalArgumentException("description is required");
    var requestHash = requestHash(request);
    lockIdempotencyKey(db, request.customerId(), key);
    var existing = db.sql("select * from roadside_incident where customer_id=:customer and idempotency_key=:key")
        .param("customer", request.customerId()).param("key", key).query(this::mapExisting).optional();
    if (existing.isPresent()) {
      if (existing.get().requestHash() != null && !existing.get().requestHash().equals(requestHash)) {
        throw new Conflict("Idempotency-Key was reused with a different request");
      }
      return existing.get().incident();
    }
    var id = UUID.randomUUID();
    var createdAt = OffsetDateTime.now(clock);
    db.sql("insert into roadside_incident(id,customer_id,vehicle_id,location,description,status,idempotency_key,request_hash,created_at) values (:id,:customer,:vehicle,:location,:description,'OPEN',:key,:requestHash,:createdAt)")
        .param("id", id).param("customer", request.customerId()).param("vehicle", request.vehicleId())
        .param("location", request.location()).param("description", request.description())
        .param("key", key).param("requestHash", requestHash).param("createdAt", createdAt).update();
    return new RoadsideContracts.Incident(id, request.customerId(), request.vehicleId(), "OPEN",
        request.location(), request.description(), createdAt);
  }

  public Optional<RoadsideContracts.Incident> find(UUID id) {
    return db.sql("select * from roadside_incident where id=:id").param("id", id).query(this::map).optional();
  }

  public List<RoadsideContracts.Incident> list(UUID customerId, int limit) {
    if (customerId == null) throw new IllegalArgumentException("customerId is required");
    if (limit < 1 || limit > 50) throw new IllegalArgumentException("limit must be between 1 and 50");
    return db.sql("select * from roadside_incident where customer_id=:customer and status <> 'CANCELLED' order by created_at desc limit :limit")
        .param("customer", customerId).param("limit", limit).query(this::map).list();
  }

  /** Incidents awaiting a truck for one hub, plus any with no hub, oldest first. */
  public List<RoadsideContracts.Incident> listDispatchable(UUID hub, int limit) {
    if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be between 1 and 100");
    return db.sql(
            "select * from roadside_incident where status in "
                + IncidentStatus.awaitingDispatchSql()
                + " and (hub_id=:hub or hub_id is null) order by created_at asc limit :limit")
        .param("hub", hub).param("limit", limit).query(this::map).list();
  }

  @Transactional
  public RoadsideContracts.Incident cancel(UUID id) {
    var incident = db.sql("select * from roadside_incident where id=:id for update")
        .param("id", id).query(this::map).optional()
        .orElseThrow(() -> new NotFound("Roadside incident not found"));
    var status = IncidentStatus.valueOf(incident.status());
    if (status == IncidentStatus.CANCELLED) return incident;
    if (!status.awaitingDispatch()) {
      throw new Conflict("Roadside incident is no longer cancellable");
    }
    db.sql("update roadside_incident set status='CANCELLED' where id=:id")
        .param("id", id).update();
    // Nothing was collected for a cancelled request, so its recorded amount is no longer owed.
    db.sql("update roadside_payment set status='VOID' where incident_id=:id and status='DUE'")
        .param("id", id).update();
    db.sql("insert into outbox_event(id,event_type,aggregate_id,payload) values (:id,'RoadsideIncidentCancelled',:aggregate,cast(:payload as jsonb))")
        .param("id", UUID.randomUUID()).param("aggregate", id)
        .param("payload", json.writeValueAsString(Map.of("incidentId", id))).update();
    return new RoadsideContracts.Incident(incident.id(), incident.customerId(), incident.vehicleId(),
        "CANCELLED", incident.location(), incident.description(), incident.createdAt());
  }

  @Transactional
  public RoadsideContracts.RecoveryAssignment assign(UUID incidentId,
                                                       RoadsideContracts.AssignRecovery request) {
    if (request.provider() == null || request.provider().isBlank()) {
      throw new IllegalArgumentException("provider is required");
    }
    if (request.quoteReference() == null || request.quoteReference().isBlank()) {
      throw new IllegalArgumentException("quoteReference is required");
    }
    if (request.amountMinor() < 0) throw new IllegalArgumentException("amountMinor cannot be negative");
    if (request.currency() == null || !request.currency().matches("[A-Z]{3}")) {
      throw new IllegalArgumentException("currency must be a three-letter code");
    }
    if (request.estimatedMinutes() < 1 || request.estimatedMinutes() > 24 * 60) {
      throw new IllegalArgumentException("estimatedMinutes must be between 1 and 1440");
    }
    var incident = db.sql("select * from roadside_incident where id=:id for update")
        .param("id", incidentId).query(this::map).optional()
        .orElseThrow(() -> new NotFound("Roadside incident not found"));
    if (IncidentStatus.valueOf(incident.status()) != IncidentStatus.OPEN) {
      throw new Conflict("Roadside incident is no longer assignable");
    }
    var assignmentId = UUID.randomUUID();
    var createdAt = OffsetDateTime.now(clock);
    db.sql("insert into roadside_assignment(id,incident_id,provider,quote_reference,amount_minor,currency,estimated_minutes,ride_home,status,created_at) values (:id,:incident,:provider,:quote,:amount,:currency,:minutes,:rideHome,'ASSIGNED',:createdAt)")
        .param("id", assignmentId).param("incident", incidentId).param("provider", request.provider())
        .param("quote", request.quoteReference()).param("amount", request.amountMinor())
        .param("currency", request.currency()).param("minutes", request.estimatedMinutes())
        .param("rideHome", request.rideHome()).param("createdAt", createdAt).update();
    db.sql("update roadside_incident set status='ASSIGNED' where id=:id").param("id", incidentId).update();
    db.sql("insert into outbox_event(id,event_type,aggregate_id,payload) values (:id,'RoadsideRecoveryAssigned',:aggregate,cast(:payload as jsonb))")
        .param("id", UUID.randomUUID()).param("aggregate", incidentId)
        .param("payload", json.writeValueAsString(Map.of("incidentId", incidentId, "assignmentId", assignmentId))).update();
    return new RoadsideContracts.RecoveryAssignment(assignmentId, incidentId, request.provider(),
        request.quoteReference(), request.amountMinor(), request.currency(), request.estimatedMinutes(),
        request.rideHome(), "ASSIGNED", createdAt);
  }

  /**
   * Serializes same-key incident creation across both create paths, which share one unique key,
   * so a concurrent retry waits and replays instead of failing on the constraint.
   */
  static void lockIdempotencyKey(JdbcClient db, UUID customer, String key) {
    db.sql("select pg_advisory_xact_lock(hashtextextended(:lock,0))")
        .param("lock", "roadside:" + customer + ":" + key).query((rs, row) -> true).single();
  }

  static String sha256(String canonical) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is unavailable", error);
    }
  }

  private RoadsideContracts.Incident map(ResultSet rs, int row) throws SQLException {
    return new RoadsideContracts.Incident(rs.getObject("id", UUID.class), rs.getObject("customer_id", UUID.class),
        rs.getObject("vehicle_id", UUID.class), rs.getString("status"), rs.getString("location"),
        rs.getString("description"), rs.getObject("created_at", OffsetDateTime.class));
  }

  private ExistingIncident mapExisting(ResultSet rs, int row) throws SQLException {
    return new ExistingIncident(map(rs, row), rs.getString("request_hash"));
  }

  private String requestHash(RoadsideContracts.CreateIncident request) {
    var canonical = String.join("\u001f", request.customerId().toString(),
        request.vehicleId() == null ? "" : request.vehicleId().toString(),
        request.location(), request.description());
    return sha256(canonical);
  }

  private record ExistingIncident(RoadsideContracts.Incident incident, String requestHash) {}
}
