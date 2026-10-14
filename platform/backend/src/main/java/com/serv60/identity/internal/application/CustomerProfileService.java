package com.serv60.identity.internal.application;

import com.serv60.identity.api.CustomerProfileContracts.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CustomerProfileService {
  private final JdbcClient db;

  public CustomerProfileService(JdbcClient db) {
    this.db = db;
  }

  public Profile get(UUID customerId) {
    ensure(customerId);
    var namePhone =
        db.sql(
                "select customer_name, phone_number from customer_profile where customer_id=:id")
            .param("id", customerId)
            .query(
                (rs, row) ->
                    Map.entry(rs.getString("customer_name"), rs.getString("phone_number")))
            .single();
    var vehicles =
        db.sql(
                "select id, vehicle_make, vehicle_model, registration, is_default from"
                    + " customer_vehicle where customer_id=:id order by is_default desc, created_at")
            .param("id", customerId)
            .query(this::mapVehicle)
            .list();
    return new Profile(customerId, namePhone.getKey(), namePhone.getValue(), vehicles);
  }

  @Transactional
  public Profile save(UUID customerId, SaveProfile request) {
    ensure(customerId);
    if (request.vehicles() != null && request.vehicles().size() > 5) {
      throw new IllegalArgumentException("You can save up to 5 vehicles");
    }
    long defaults =
        request.vehicles() == null
            ? 0
            : request.vehicles().stream().filter(SaveVehicle::isDefault).count();
    if (defaults > 1) throw new IllegalArgumentException("Choose one default vehicle");
    db.sql(
            "update customer_profile set customer_name=:name, phone_number=:phone, updated_at=now()"
                + " where customer_id=:id")
        .param("name", request.customerName().trim())
        .param("phone", request.phoneNumber().trim())
        .param("id", customerId)
        .update();
    db.sql("delete from customer_vehicle where customer_id=:id")
        .param("id", customerId)
        .update();
    if (request.vehicles() != null) {
      boolean first = true;
      for (var vehicle : request.vehicles()) {
        boolean isDefault = vehicle.isDefault() || (defaults == 0 && first);
        first = false;
        db.sql(
                "insert into customer_vehicle(id,customer_id,vehicle_make,vehicle_model,registration,is_default)"
                    + " values (:id,:customer,:make,:model,:reg,:def)")
            .param("id", vehicle.id() == null ? UUID.randomUUID() : vehicle.id())
            .param("customer", customerId)
            .param("make", vehicle.vehicleMake().trim())
            .param("model", vehicle.vehicleModel().trim())
            .param("reg", vehicle.registration().trim())
            .param("def", isDefault)
            .update();
      }
    }
    return get(customerId);
  }

  private void ensure(UUID customerId) {
    db.sql(
            "insert into customer_profile(customer_id) values (:id) on conflict (customer_id) do"
                + " nothing")
        .param("id", customerId)
        .update();
  }

  private Vehicle mapVehicle(ResultSet rs, int row) throws SQLException {
    return new Vehicle(
        rs.getObject("id", UUID.class),
        rs.getString("vehicle_make"),
        rs.getString("vehicle_model"),
        rs.getString("registration"),
        rs.getBoolean("is_default"));
  }
}
