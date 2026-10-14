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

  /**
   * Saves name, phone and vehicles. Vehicles keep their ids across saves so bookings keep pointing
   * at them; an id the customer does not own is treated as a new vehicle, never claimed.
   */
  @Transactional
  public Profile save(UUID customerId, SaveProfile request) {
    ensure(customerId);
    var vehicles = request.vehicles() == null ? List.<SaveVehicle>of() : request.vehicles();
    if (vehicles.size() > 5) {
      throw new IllegalArgumentException("You can save up to 5 vehicles");
    }
    long defaults = vehicles.stream().filter(SaveVehicle::isDefault).count();
    if (defaults > 1) throw new IllegalArgumentException("Choose one default vehicle");
    // Serialize concurrent saves for one customer; the default-vehicle index is per customer.
    db.sql("select customer_id from customer_profile where customer_id=:id for update")
        .param("id", customerId)
        .query(UUID.class)
        .single();
    db.sql(
            "update customer_profile set customer_name=:name, phone_number=:phone, updated_at=now()"
                + " where customer_id=:id")
        .param("name", request.customerName().trim())
        .param("phone", request.phoneNumber().trim())
        .param("id", customerId)
        .update();
    var owned =
        new HashSet<>(
            db.sql("select id from customer_vehicle where customer_id=:id")
                .param("id", customerId)
                .query(UUID.class)
                .list());
    // Clear defaults first so the one-default-per-customer index never sees two at once.
    db.sql("update customer_vehicle set is_default=false where customer_id=:id")
        .param("id", customerId)
        .update();
    var kept = new ArrayList<UUID>();
    for (int i = 0; i < vehicles.size(); i++) {
      var vehicle = vehicles.get(i);
      boolean isDefault = vehicle.isDefault() || (defaults == 0 && i == 0);
      if (vehicle.id() != null && owned.contains(vehicle.id())) {
        db.sql(
                "update customer_vehicle set vehicle_make=:make, vehicle_model=:model,"
                    + " registration=:reg, is_default=:def where id=:vid and customer_id=:customer")
            .param("make", vehicle.vehicleMake().trim())
            .param("model", vehicle.vehicleModel().trim())
            .param("reg", vehicle.registration().trim())
            .param("def", isDefault)
            .param("vid", vehicle.id())
            .param("customer", customerId)
            .update();
        kept.add(vehicle.id());
      } else {
        var id = UUID.randomUUID();
        db.sql(
                "insert into customer_vehicle(id,customer_id,vehicle_make,vehicle_model,registration,is_default)"
                    + " values (:id,:customer,:make,:model,:reg,:def)")
            .param("id", id)
            .param("customer", customerId)
            .param("make", vehicle.vehicleMake().trim())
            .param("model", vehicle.vehicleModel().trim())
            .param("reg", vehicle.registration().trim())
            .param("def", isDefault)
            .update();
        kept.add(id);
      }
    }
    owned.removeAll(kept);
    for (var removed : owned) {
      // Bookings that used this vehicle keep their contact snapshot; the FK nulls the reference.
      db.sql("delete from customer_vehicle where id=:id and customer_id=:customer")
          .param("id", removed)
          .param("customer", customerId)
          .update();
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
