package com.serv60.roadside.internal.application;

import com.serv60.roadside.api.RoadsideContracts;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Optional;
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
    var existing = db.sql("select * from roadside_incident where customer_id=:customer and idempotency_key=:key")
        .param("customer", request.customerId()).param("key", key).query(this::map).optional();
    if (existing.isPresent()) return existing.get();
    var id = UUID.randomUUID();
    var createdAt = OffsetDateTime.now(clock);
    db.sql("insert into roadside_incident(id,customer_id,vehicle_id,location,description,status,idempotency_key,created_at) values (:id,:customer,:vehicle,:location,:description,'OPEN',:key,:createdAt)")
        .param("id", id).param("customer", request.customerId()).param("vehicle", request.vehicleId())
        .param("location", request.location()).param("description", request.description())
        .param("key", key).param("createdAt", createdAt).update();
    return new RoadsideContracts.Incident(id, request.customerId(), request.vehicleId(), "OPEN",
        request.location(), request.description(), createdAt);
  }

  public Optional<RoadsideContracts.Incident> find(UUID id) {
    return db.sql("select * from roadside_incident where id=:id").param("id", id).query(this::map).optional();
  }

  private RoadsideContracts.Incident map(ResultSet rs, int row) throws SQLException {
    return new RoadsideContracts.Incident(rs.getObject("id", UUID.class), rs.getObject("customer_id", UUID.class),
        rs.getObject("vehicle_id", UUID.class), rs.getString("status"), rs.getString("location"),
        rs.getString("description"), rs.getObject("created_at", OffsetDateTime.class));
  }
}
