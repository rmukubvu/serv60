package com.serv60.booking.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.serv60.booking.api.BookingContracts;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest
@Testcontainers
class BookingServiceIntegrationTest {
  private static final UUID HUB = UUID.fromString("00000000-0000-0000-0000-000000000060");

  @Container
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
  }

  @Autowired BookingService bookings;
  @Autowired JdbcClient db;

  @Test
  void holdsAreIdempotentAndConfirmationConsumesStock() {
    UUID customer = UUID.randomUUID();
    OffsetDateTime start = OffsetDateTime.now(ZoneOffset.UTC).plusHours(2).withNano(0);
    var request = new BookingContracts.CreateHold(HUB, customer, null, "EXPRESS_OIL",
        start, start.plusHours(1), "READY_STOCK");

    var first = bookings.createHold(request, "booking-1");
    var replay = bookings.createHold(request, "booking-1");

    assertThat(replay.id()).isEqualTo(first.id());
    assertThat(first.status()).isEqualTo("HELD");
    assertThat(db.sql("select reserved_quantity from stock_balance where hub_id=:hub and sku='OIL-5W30'")
        .param("hub", HUB).query(Integer.class).single()).isEqualTo(1);

    var confirmed = bookings.confirm(first.id());
    assertThat(confirmed.status()).isEqualTo("CONFIRMED");
    assertThat(db.sql("select status from stock_reservation where hold_id=:id")
        .param("id", first.id()).query(String.class).single()).isEqualTo("CONFIRMED");
  }

  @Test
  void overlappingResourceIsRejectedAndConditionalPolicyCreatesDemand() {
    UUID customer = UUID.randomUUID();
    OffsetDateTime start = OffsetDateTime.now(ZoneOffset.UTC).plusHours(4).withNano(0);
    var firstRequest = new BookingContracts.CreateHold(HUB, customer, null, "EXPRESS_OIL",
        start, start.plusHours(1), "CONDITIONAL_PROCUREMENT");
    var first = bookings.createHold(firstRequest, "booking-2");
    for (int i = 0; i < 3; i++) {
      var fill = new BookingContracts.CreateHold(HUB, UUID.randomUUID(), null, "EXPRESS_OIL",
          start, start.plusHours(1), "CONDITIONAL_PROCUREMENT");
      bookings.createHold(fill, "booking-fill-" + i);
    }

    var competing = new BookingContracts.CreateHold(HUB, UUID.randomUUID(), null, "EXPRESS_OIL",
        start.plusMinutes(15), start.plusMinutes(75), "CONDITIONAL_PROCUREMENT");
    assertThatThrownBy(() -> bookings.createHold(competing, "booking-3"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("No compatible resource");

    assertThat(db.sql("select count(*) from parts_demand where hold_id=:id")
        .param("id", first.id()).query(Long.class).single()).isEqualTo(2);
  }
}
