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
import org.springframework.beans.factory.annotation.Value;
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
  private final int maxActivePerCustomer;

  public RoadsideService(
      JdbcClient db,
      Clock clock,
      ObjectMapper json,
      @Value("${serv60.roadside.max-active-per-customer:3}") int maxActivePerCustomer) {
    this.json = json;
    this.db = db;
    this.clock = clock;
    this.maxActivePerCustomer = maxActivePerCustomer;
  }

  @Transactional
  public RoadsideContracts.Incident create(RoadsideContracts.CreateIncident request, String key) {
    if (key == null || key.isBlank()) throw new IllegalArgumentException("Idempotency-Key is required");
    if (request.customerId() == null) throw new IllegalArgumentException("customerId is required");
    if (request.hubId() == null) throw new IllegalArgumentException("hubId is required");
    if (request.location() == null || request.location().isBlank()) throw new IllegalArgumentException("location is required");
    if (request.description() == null || request.description().isBlank()) throw new IllegalArgumentException("description is required");
    var requestHash = requestHash(request);
    lockCustomer(db, request.customerId());
    var existing = db.sql("select * from roadside_incident where customer_id=:customer and idempotency_key=:key")
        .param("customer", request.customerId()).param("key", key).query(this::mapExisting).optional();
    if (existing.isPresent()) {
      if (existing.get().requestHash() != null && !existing.get().requestHash().equals(requestHash)) {
        throw new Conflict("Idempotency-Key was reused with a different request");
      }
      return existing.get().incident();
    }
    requireOwnedVehicle(db, request.customerId(), request.vehicleId());
    requireUnderActiveCap(db, request.customerId(), maxActivePerCustomer);
    var id = UUID.randomUUID();
    var createdAt = OffsetDateTime.now(clock);
    db.sql("insert into roadside_incident(id,customer_id,vehicle_id,location,description,status,idempotency_key,request_hash,created_at,hub_id) values (:id,:customer,:vehicle,:location,:description,'OPEN',:key,:requestHash,:createdAt,:hub)")
        .param("id", id).param("customer", request.customerId()).param("vehicle", request.vehicleId())
        .param("hub", request.hubId())
        .param("location", request.location()).param("description", request.description())
        .param("key", key).param("requestHash", requestHash).param("createdAt", createdAt).update();
    return new RoadsideContracts.Incident(id, request.customerId(), request.vehicleId(), "OPEN",
        request.location(), request.description(), createdAt);
  }

  /** The hub an incident belongs to, or null when none was published at creation. */
  public UUID hubOf(UUID incidentId) {
    return db.sql("select hub_id from roadside_incident where id=:id").param("id", incidentId)
        .query(UUID.class).optional().orElse(null);
  }

  public Optional<RoadsideContracts.Incident> find(UUID id) {
    return db.sql("select * from roadside_incident where id=:id").param("id", id).query(this::map).optional();
  }

  public List<RoadsideContracts.Incident> list(UUID customerId, int limit) {
    if (customerId == null) throw new IllegalArgumentException("customerId is required");
    if (limit < 1 || limit > 50) throw new IllegalArgumentException("limit must be between 1 and 50");
    return db.sql("select * from roadside_incident where customer_id=:customer and status not in ('CANCELLED','EXPIRED') order by created_at desc limit :limit")
        .param("customer", customerId).param("limit", limit).query(this::map).list();
  }

  /** Incidents awaiting a truck for one hub, oldest first. */
  public List<RoadsideContracts.Incident> listDispatchable(UUID hub, int limit) {
    if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be between 1 and 100");
    return db.sql(
            "select * from roadside_incident where status in "
                + IncidentStatus.awaitingDispatchSql()
                + " and hub_id=:hub order by created_at asc limit :limit")
        .param("hub", hub).param("limit", limit).query(this::map).list();
  }

  /**
   * Cancels a request. The customer may cancel until a truck is confirmed; after that only the
   * hub's dispatcher may, because the partner has to be called off. Cancelling twice is a no-op.
   */
  @Transactional
  public RoadsideContracts.Incident cancel(UUID id, boolean byOperator) {
    var incident = db.sql("select * from roadside_incident where id=:id for update")
        .param("id", id).query(this::map).optional()
        .orElseThrow(() -> new NotFound("Roadside incident not found"));
    var status = IncidentStatus.valueOf(incident.status());
    if (status == IncidentStatus.CANCELLED) return incident;
    if (status.terminal()) {
      throw new Conflict("Roadside incident is already closed");
    }
    if (!byOperator && !status.awaitingDispatch()) {
      throw new Conflict("A truck is on its way. Call the hub to cancel");
    }
    db.sql("update roadside_incident set status='CANCELLED' where id=:id")
        .param("id", id).update();
    // Nothing was collected for a cancelled request, so its recorded amount is no longer owed.
    db.sql("update roadside_payment set status='VOID' where incident_id=:id and status='DUE'")
        .param("id", id).update();
    db.sql("update roadside_dispatch_offer set status='CANCELLED' where incident_id=:id and status='OFFERED'")
        .param("id", id).update();
    db.sql("update roadside_ride set status='CANCELLED' where incident_id=:id")
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
   * Serializes one customer's incident creation across both create paths. A same-key retry waits
   * and replays instead of failing on the unique key, and the active-request cap cannot be raced.
   */
  static void lockCustomer(JdbcClient db, UUID customer) {
    db.sql("select pg_advisory_xact_lock(hashtextextended(:lock,0))")
        .param("lock", "roadside-customer:" + customer).query((rs, row) -> true).single();
  }

  /** A vehicle on the request must be one of this customer's saved vehicles. */
  static void requireOwnedVehicle(JdbcClient db, UUID customer, UUID vehicleId) {
    if (vehicleId == null) return;
    boolean owned = db.sql("select exists(select 1 from customer_vehicle where id=:id and customer_id=:customer)")
        .param("id", vehicleId).param("customer", customer).query(Boolean.class).single();
    if (!owned) throw new IllegalArgumentException("Vehicle is not on this customer profile");
  }

  /** Refuses a new request while the customer already has {@code cap} open ones. */
  static void requireUnderActiveCap(JdbcClient db, UUID customer, int cap) {
    int open = db.sql("select count(*) from roadside_incident where customer_id=:customer and status in "
            + IncidentStatus.activeSql())
        .param("customer", customer).query(Integer.class).single();
    if (open >= cap) {
      throw new Conflict("You already have " + open + " open roadside requests. Cancel one first");
    }
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
