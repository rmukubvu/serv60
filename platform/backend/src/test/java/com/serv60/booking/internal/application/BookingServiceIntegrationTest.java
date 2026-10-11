package com.serv60.booking.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.serv60.booking.api.BookingContracts;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest
@TestPropertySource(properties = {"spring.flyway.enabled=false", "serv60.booking.expiry-sweep-enabled=false"})
@Sql(scripts = {"/db/migration/V1__platform_schema.sql", "/db/migration/V2__outbox_leasing.sql", "/db/migration/V3__booking_idempotency_hash.sql"}, executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
class BookingServiceIntegrationTest {
  private static final UUID HUB = UUID.fromString("00000000-0000-0000-0000-000000000060");

  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18-alpine");

  static { POSTGRES.start(); }

  @org.junit.jupiter.api.AfterAll
  static void stopDatabase() { POSTGRES.stop(); }

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
    registry.add("spring.flyway.user", POSTGRES::getUsername);
    registry.add("spring.flyway.password", POSTGRES::getPassword);
  }

  @Autowired BookingService bookings;
  @Autowired JdbcClient db;
  @Autowired OutboxLeaseService outbox;

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
    assertThat(db.sql("select status from stock_reservation where hold_id=:id and sku='OIL-5W30'")
        .param("id", first.id()).query(String.class).single()).isEqualTo("CONFIRMED");
  }

  @Test
  void rejectsIdempotencyKeyReuseWithDifferentPayload() {
    OffsetDateTime start = OffsetDateTime.now(ZoneOffset.UTC).plusHours(3).withNano(0);
    var first = new BookingContracts.CreateHold(HUB, UUID.randomUUID(), null, "PRIVATE_WORK",
        start, start.plusHours(1), "CONDITIONAL_PROCUREMENT");
    bookings.createHold(first, "booking-hash");
    var changed = new BookingContracts.CreateHold(HUB, first.customerId(), null, "PRIVATE_WORK",
        start.plusMinutes(15), start.plusMinutes(75), "CONDITIONAL_PROCUREMENT");

    assertThatThrownBy(() -> bookings.createHold(changed, "booking-hash"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("different request");
  }

  @Test
  void partsReadinessCombinesStockAndPendingDemand() {
    OffsetDateTime start = OffsetDateTime.now(ZoneOffset.UTC).plusHours(5).withNano(0);
    var request = new BookingContracts.CreateHold(HUB, UUID.randomUUID(), null, "EXPRESS_OIL",
        start, start.plusHours(1), "CONDITIONAL_PROCUREMENT");
    bookings.createHold(request, "booking-parts-readiness");

    var readiness = bookings.partsReadiness(HUB, 2);

    assertThat(readiness).hasSize(2);
    assertThat(readiness).anyMatch(row -> row.sku().equals("OIL-5W30") && row.pendingDemand() >= 1);
    assertThat(readiness).allMatch(row -> row.usableQuantity() >= 0 && row.reservedQuantity() >= 0);
  }

  @Test
  void visitStatusCanBeReadAfterCreatingHold() {
    OffsetDateTime start = OffsetDateTime.now(ZoneOffset.UTC).plusHours(6).withNano(0);
    var request = new BookingContracts.CreateHold(HUB, UUID.randomUUID(), null, "PRIVATE_WORK",
        start, start.plusHours(1), "CONDITIONAL_PROCUREMENT");
    var created = bookings.createHold(request, "booking-status");

    var found = bookings.findHold(created.id());
    assertThat(found).isPresent();
    assertThat(found.orElseThrow()).extracting(BookingContracts.Hold::id, BookingContracts.Hold::hubId,
        BookingContracts.Hold::customerId, BookingContracts.Hold::serviceCode, BookingContracts.Hold::status,
        BookingContracts.Hold::policy)
        .containsExactly(created.id(), created.hubId(), created.customerId(), created.serviceCode(),
            created.status(), created.policy());
    assertThat(bookings.findHold(UUID.randomUUID())).isEmpty();
  }

  @Test
  void cancellationReleasesStockAndResourcesAndIsIdempotent() {
    OffsetDateTime start = OffsetDateTime.now(ZoneOffset.UTC).plusHours(7).withNano(0);
    var request = new BookingContracts.CreateHold(HUB, UUID.randomUUID(), null, "EXPRESS_OIL",
        start, start.plusHours(1), "READY_STOCK");
    var created = bookings.createHold(request, "booking-cancel");
    var reservedBeforeCancel = db.sql("select reserved_quantity from stock_balance where hub_id=:hub and sku='OIL-5W30'")
        .param("hub", HUB).query(Integer.class).single();

    var cancelled = bookings.cancel(created.id());
    var replay = bookings.cancel(created.id());

    assertThat(cancelled.status()).isEqualTo("CANCELLED");
    assertThat(replay.status()).isEqualTo("CANCELLED");
    assertThat(db.sql("select reserved_quantity from stock_balance where hub_id=:hub and sku='OIL-5W30'")
        .param("hub", HUB).query(Integer.class).single()).isEqualTo(reservedBeforeCancel - 1);
    assertThat(db.sql("select status from stock_reservation where hold_id=:id and sku='OIL-5W30'")
        .param("id", created.id()).query(String.class).single()).isEqualTo("RELEASED");
    assertThat(db.sql("select status from resource_allocation where hold_id=:id")
        .param("id", created.id()).query(String.class).single()).isEqualTo("RELEASED");
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

  @Test
  void expiryReleasesResourcesAndReservedStock() {
    OffsetDateTime start = OffsetDateTime.now(ZoneOffset.UTC).plusHours(8).withNano(0);
    var request = new BookingContracts.CreateHold(HUB, UUID.randomUUID(), null, "EXPRESS_OIL",
        start, start.plusHours(1), "READY_STOCK");
    var hold = bookings.createHold(request, "booking-expiry");

    assertThat(bookings.expireDueHolds(hold.expiresAt().plusSeconds(1))).isGreaterThanOrEqualTo(1);
    assertThat(db.sql("select status from visit_hold where id=:id").param("id", hold.id())
        .query(String.class).single()).isEqualTo("EXPIRED");
    assertThat(db.sql("select status from stock_reservation where hold_id=:id and sku='OIL-5W30'")
        .param("id", hold.id()).query(String.class).single()).isEqualTo("RELEASED");
    assertThat(db.sql("select status from resource_allocation where hold_id=:id").param("id", hold.id())
        .query(String.class).single()).isEqualTo("RELEASED");
  }

  @Test
  void outboxLeaseCanBeClaimedReleasedAndPublished() {
    OffsetDateTime start = OffsetDateTime.now(ZoneOffset.UTC).plusHours(10).withNano(0);
    var request = new BookingContracts.CreateHold(HUB, UUID.randomUUID(), null, "PRIVATE_WORK",
        start, start.plusHours(1), "CONDITIONAL_PROCUREMENT");
    bookings.createHold(request, "booking-outbox");

    var claimed = outbox.claim(10, Duration.ofMinutes(2));
    assertThat(claimed).isNotEmpty();
    var event = claimed.getLast();
    assertThat(event.attemptCount()).isEqualTo(1);

    outbox.release(event.id(), "temporary SQS timeout", Duration.ofMinutes(1));
    assertThat(db.sql("select last_error from outbox_event where id=:id").param("id", event.id())
        .query(String.class).single()).isEqualTo("temporary SQS timeout");
    outbox.markPublished(event.id());
    assertThat(db.sql("select published_at from outbox_event where id=:id").param("id", event.id())
        .query(Object.class).single()).isNotNull();
  }
}
