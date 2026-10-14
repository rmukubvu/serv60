package com.serv60.identity.internal.application;

import static org.assertj.core.api.Assertions.*;

import com.serv60.identity.api.CustomerProfileContracts.*;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.*;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(
    properties = {"serv60.booking.expiry-sweep-enabled=false", "serv60.security.enabled=false"})
@Transactional
class CustomerProfileIntegrationTest {
  static final PostgreSQLContainer PG = new PostgreSQLContainer("postgres:18-alpine");

  static {
    PG.start();
  }

  @AfterAll
  static void stop() {
    PG.stop();
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", PG::getJdbcUrl);
    r.add("spring.datasource.username", PG::getUsername);
    r.add("spring.datasource.password", PG::getPassword);
  }

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
