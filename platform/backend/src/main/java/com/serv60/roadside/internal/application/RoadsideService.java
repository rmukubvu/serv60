package com.serv60.roadside.internal.application;

import com.serv60.roadside.api.RoadsideContracts;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Optional;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RoadsideService {
  private final JdbcClient db;
  private final Clock clock;

  public RoadsideService(JdbcClient db, Clock clock) {
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
    var existing = db.sql("select * from roadside_incident where customer_id=:customer and idempotency_key=:key")
        .param("customer", request.customerId()).param("key", key).query(this::mapExisting).optional();
    if (existing.isPresent()) {
      if (existing.get().requestHash() != null && !existing.get().requestHash().equals(requestHash)) {
        throw new IllegalStateException("Idempotency-Key was reused with a different request");
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

  @Transactional
  public RoadsideContracts.Incident cancel(UUID id) {
    var incident = db.sql("select * from roadside_incident where id=:id for update")
        .param("id", id).query(this::map).optional()
        .orElseThrow(() -> new IllegalArgumentException("Roadside incident not found"));
    if (incident.status().equals("CANCELLED")) return incident;
    if (!incident.status().equals("OPEN")) {
      throw new IllegalStateException("Roadside incident is no longer cancellable");
    }
    db.sql("update roadside_incident set status='CANCELLED' where id=:id")
        .param("id", id).update();
    db.sql("insert into outbox_event(id,event_type,aggregate_id,payload) values (:id,'RoadsideIncidentCancelled',:aggregate,cast(:payload as jsonb))")
        .param("id", UUID.randomUUID()).param("aggregate", id)
        .param("payload", "{\"incidentId\":\"" + id + "\"}").update();
    return new RoadsideContracts.Incident(incident.id(), incident.customerId(), incident.vehicleId(),
        "CANCELLED", incident.location(), incident.description(), incident.createdAt());
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
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is unavailable", error);
    }
  }

  private record ExistingIncident(RoadsideContracts.Incident incident, String requestHash) {}
}
