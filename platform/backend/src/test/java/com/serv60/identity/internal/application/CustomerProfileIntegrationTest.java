package com.serv60.identity.internal.application;

import static org.assertj.core.api.Assertions.*;

import com.serv60.identity.api.CustomerProfileContracts.*;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.*;
import org.springframework.transaction.annotation.Transactional;
import com.serv60.support.PlatformIntegrationTest;

@PlatformIntegrationTest
@Transactional
class CustomerProfileIntegrationTest {
  @Autowired CustomerProfileService profiles;
  final UUID customer = UUID.randomUUID();

  SaveVehicle vehicle(String registration, boolean isDefault) {
    return new SaveVehicle(null, "Toyota", "Corolla", registration, isDefault);
  }

  @Test
  void shouldCreateAnEmptyProfileOnFirstRead() {
    var profile = profiles.get(customer);

    assertThat(profile).isEqualTo(new Profile(customer, "", "", List.of()));
  }

  @Test
  void shouldTrimDetailsAndDefaultTheFirstVehicleWhenNoneIsChosen() {
    var saved =
        profiles.save(
            customer, new SaveProfile(" Alex ", " +27000000000 ", List.of(vehicle("CA 1", false), vehicle("CA 2", false))));

    assertThat(saved.customerName()).isEqualTo("Alex");
    assertThat(saved.phoneNumber()).isEqualTo("+27000000000");
    assertThat(saved.vehicles()).extracting(Vehicle::isDefault).containsExactly(true, false);
  }

  @Test
  void shouldReplaceTheWholeVehicleListOnSave() {
    profiles.save(customer, new SaveProfile("Alex", "+27", List.of(vehicle("CA 1", true))));

    var replaced = profiles.save(customer, new SaveProfile("Alex", "+27", List.of(vehicle("CA 9", true))));
    var cleared = profiles.save(customer, new SaveProfile("Alex", "+27", null));

    assertThat(replaced.vehicles()).extracting(Vehicle::registration).containsExactly("CA 9");
    assertThat(cleared.vehicles()).isEmpty();
  }

  @Autowired com.serv60.booking.internal.application.BookingService bookings;
  @Autowired org.springframework.jdbc.core.simple.JdbcClient db;

  @Test
  void shouldKeepVehicleIdsAcrossSavesAndUpdateInPlace() {
    var first = profiles.save(customer, new SaveProfile("Alex", "+27", List.of(vehicle("CA 1", true))));
    var id = first.vehicles().getFirst().id();

    var edited =
        profiles.save(
            customer,
            new SaveProfile("Alex", "+27", List.of(new SaveVehicle(id, "Toyota", "Yaris", "CA 1", true))));

    assertThat(edited.vehicles()).extracting(Vehicle::id).containsExactly(id);
    assertThat(edited.vehicles().getFirst().vehicleModel()).isEqualTo("Yaris");
  }

  @Test
  void shouldNeverClaimAnotherCustomersVehicleId() {
    var other = UUID.randomUUID();
    var theirs =
        profiles.save(other, new SaveProfile("Sam", "+27", List.of(vehicle("SAM 1", true))))
            .vehicles().getFirst().id();

    var mine =
        profiles.save(
            customer,
            new SaveProfile("Alex", "+27", List.of(new SaveVehicle(theirs, "Fiat", "Uno", "CA 7", true))));

    assertThat(mine.vehicles().getFirst().id()).isNotEqualTo(theirs);
    assertThat(profiles.get(other).vehicles()).extracting(Vehicle::registration).containsExactly("SAM 1");
  }

  @Test
  void shouldDetachADeletedVehicleFromItsBookingAndRefuseForeignVehicles() {
    var vehicle =
        profiles.save(customer, new SaveProfile("Alex", "+27", List.of(vehicle("CA 1", true))))
            .vehicles().getFirst().id();
    var start = java.time.OffsetDateTime.parse("2039-05-02T08:00:00Z");
    var hub = UUID.fromString("00000000-0000-0000-0000-000000000060");
    var hold =
        bookings.createHold(
            new com.serv60.booking.api.BookingContracts.CreateHold(
                hub, customer, vehicle, "PRIVATE_WORK", start, start.plusHours(1), "READY_STOCK"),
            "vehicle-hold");

    profiles.save(customer, new SaveProfile("Alex", "+27", List.of(vehicle("CA 2", true))));

    assertThat(
            db.sql("select vehicle_id from visit_hold where id=:id")
                .param("id", hold.id())
                .query(UUID.class)
                .optional())
        .isEmpty();
    assertThatThrownBy(
            () ->
                bookings.createHold(
                    new com.serv60.booking.api.BookingContracts.CreateHold(
                        hub, UUID.randomUUID(), vehicle, "PRIVATE_WORK", start.plusHours(2),
                        start.plusHours(3), "READY_STOCK"),
                    "foreign-vehicle"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not on this customer profile");
  }

  @Test
  void shouldRejectMoreThanFiveVehiclesOrMoreThanOneDefault() {
    var six = java.util.stream.IntStream.range(0, 6).mapToObj(i -> vehicle("CA " + i, false)).toList();

    assertThatThrownBy(() -> profiles.save(customer, new SaveProfile("Alex", "+27", six)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("5 vehicles");
    assertThatThrownBy(
            () ->
                profiles.save(
                    customer,
                    new SaveProfile("Alex", "+27", List.of(vehicle("CA 1", true), vehicle("CA 2", true)))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("one default");
  }
}
