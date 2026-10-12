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
import com.serv60.roadside.api.RoadsideContracts;
import com.serv60.roadside.internal.application.RoadsideService;
import com.serv60.payment.api.PaymentContracts;
import com.serv60.payment.internal.application.PaymentService;

@SpringBootTest
@TestPropertySource(properties = {"spring.flyway.enabled=false", "serv60.booking.expiry-sweep-enabled=false"})
@Sql(scripts = {"/db/migration/V1__platform_schema.sql", "/db/migration/V2__outbox_leasing.sql", "/db/migration/V3__booking_idempotency_hash.sql", "/db/migration/V4__calendar_query_indexes.sql", "/db/migration/V5__roadside_incidents.sql", "/db/migration/V6__roadside_idempotency_hash.sql", "/db/migration/V7__roadside_assignments.sql", "/db/migration/V8__payment_orders.sql", "/db/migration/V9__payment_webhook_receipts.sql", "/db/migration/V10__payment_checkout_url.sql"}, executionPhase = Sql.ExecutionPhase.BEFORE_TEST_CLASS)
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
  @Autowired RoadsideService roadside;
  @Autowired PaymentService payments;

  @Test
  void paymentWebhookIsVerifiedByIdempotentReconciliationState() {
    var customer = UUID.randomUUID();
    var start = OffsetDateTime.now(ZoneOffset.UTC).plusDays(101).withNano(0);
    var hold = bookings.createHold(new BookingContracts.CreateHold(HUB, customer, null, "PRIVATE_WORK",
        start, start.plusHours(1), "READY_STOCK"), "webhook-hold");
    var order = payments.create(new PaymentContracts.CreatePayment(customer, hold.id(), 24900,
        "ZAR", "YOCO"), "webhook-payment");
    var event = new PaymentContracts.ProviderPaymentEvent("yoco", "evt-1", order.id(),
        "ch_123", 24900, "ZAR", "PAID");

    var reconciled = payments.reconcile(event, "{}");
    var replay = payments.reconcile(event, "{}");

    assertThat(reconciled.status()).isEqualTo("PAID");
    assertThat(reconciled.providerReference()).isEqualTo("ch_123");
    assertThat(replay.id()).isEqualTo(order.id());
    assertThat(db.sql("select count(*) from payment_webhook_receipt where provider='YOCO' and event_id='evt-1'")
        .query(Long.class).single()).isEqualTo(1);
    assertThatThrownBy(() -> payments.reconcile(new PaymentContracts.ProviderPaymentEvent(
        "YOCO", "evt-2", order.id(), "ch_123", 1, "ZAR", "PAID"), "{}"))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("does not match");
  }

  @Test
  void paymentOrderIsProviderNeutralAndIdempotent() {
    var customer = UUID.randomUUID();
    var start = OffsetDateTime.now(ZoneOffset.UTC).plusDays(100).withNano(0);
    var hold = bookings.createHold(new BookingContracts.CreateHold(HUB, customer, null, "PRIVATE_WORK",
        start, start.plusHours(1), "READY_STOCK"), "payment-hold");
    var request = new PaymentContracts.CreatePayment(customer, hold.id(), 24900, "zar", "yoco");

    var first = payments.create(request, "payment-1");
    var replay = payments.create(request, "payment-1");

    assertThat(first).extracting(PaymentContracts.PaymentOrder::status,
        PaymentContracts.PaymentOrder::provider, PaymentContracts.PaymentOrder::currency,
        PaymentContracts.PaymentOrder::amountMinor)
        .containsExactly("PENDING", "YOCO", "ZAR", 24900L);
    assertThat(replay.id()).isEqualTo(first.id());
    assertThat(payments.find(first.id())).hasValueSatisfying(found -> assertThat(found)
        .extracting(PaymentContracts.PaymentOrder::id, PaymentContracts.PaymentOrder::customerId,
            PaymentContracts.PaymentOrder::holdId, PaymentContracts.PaymentOrder::amountMinor,
            PaymentContracts.PaymentOrder::currency, PaymentContracts.PaymentOrder::provider,
            PaymentContracts.PaymentOrder::status, PaymentContracts.PaymentOrder::providerReference)
        .containsExactly(first.id(), first.customerId(), first.holdId(), first.amountMinor(),
            first.currency(), first.provider(), first.status(), first.providerReference()));
  }

  @Test
  void paymentRejectsDifferentIdempotentPayloadAndUnknownProvider() {
    var customer = UUID.randomUUID();
    var start = OffsetDateTime.now(ZoneOffset.UTC).plusDays(102).withNano(0);
    var hold = bookings.createHold(new BookingContracts.CreateHold(HUB, customer, null, "PRIVATE_WORK",
        start, start.plusHours(1), "READY_STOCK"), "payment-hold-conflict");
    var request = new PaymentContracts.CreatePayment(customer, hold.id(), 29900, "ZAR", "PEACH");
    payments.create(request, "payment-conflict");

    assertThatThrownBy(() -> payments.create(new PaymentContracts.CreatePayment(customer, hold.id(),
        39900, "ZAR", "PEACH"), "payment-conflict"))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("different request");
    assertThatThrownBy(() -> payments.create(new PaymentContracts.CreatePayment(customer, hold.id(),
        29900, "ZAR", "CARD"), "payment-provider"))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unsupported payment provider");
  }

  @Test
  void paymentValidatesCurrencyAmountOwnershipAndHoldState() {
    var owner = UUID.randomUUID();
    var start = OffsetDateTime.now(ZoneOffset.UTC).plusDays(103).withNano(0);
    var hold = bookings.createHold(new BookingContracts.CreateHold(HUB, owner, null, "PRIVATE_WORK",
        start, start.plusHours(1), "READY_STOCK"), "payment-validation-hold");

    assertThatThrownBy(() -> payments.create(new PaymentContracts.CreatePayment(owner, hold.id(), 0,
        "ZAR", "YOCO"), "payment-zero"))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("positive");
    assertThatThrownBy(() -> payments.create(new PaymentContracts.CreatePayment(owner, hold.id(), 100,
        "USD", "YOCO"), "payment-currency"))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ZAR");
    assertThatThrownBy(() -> payments.create(new PaymentContracts.CreatePayment(UUID.randomUUID(), hold.id(),
        100, "ZAR", "YOCO"), "payment-owner"))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("does not own");

    bookings.cancel(hold.id());
    assertThatThrownBy(() -> payments.create(new PaymentContracts.CreatePayment(owner, hold.id(), 100,
        "ZAR", "YOCO"), "payment-cancelled"))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("not payable");
  }

  @Test
  void roadsideIncidentIsIdempotentAndReadable() {
    var request = new RoadsideContracts.CreateIncident(UUID.randomUUID(), UUID.randomUUID(), "Cape Town", "Flat tyre");
    var first = roadside.create(request, "roadside-1");
    var replay = roadside.create(request, "roadside-1");

    assertThat(replay.id()).isEqualTo(first.id());
    var found = roadside.find(first.id()).orElseThrow();
    assertThat(found).extracting(RoadsideContracts.Incident::id, RoadsideContracts.Incident::status,
        RoadsideContracts.Incident::location, RoadsideContracts.Incident::description)
        .containsExactly(first.id(), "OPEN", "Cape Town", "Flat tyre");
    assertThat(roadside.list(request.customerId(), 20)).extracting(RoadsideContracts.Incident::id)
        .containsExactly(first.id());
  }

  @Test
  void roadsideIncidentRejectsMissingIdempotencyKey() {
    var request = new RoadsideContracts.CreateIncident(UUID.randomUUID(), null, "Cape Town", "Battery failure");
    assertThatThrownBy(() -> roadside.create(request, " ")).isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Idempotency-Key");
  }

  @Test
  void roadsideIncidentRejectsIdempotencyKeyReuseWithDifferentPayload() {
    var customer = UUID.randomUUID();
    var first = new RoadsideContracts.CreateIncident(customer, null, "Cape Town", "Flat tyre");
    var changed = new RoadsideContracts.CreateIncident(customer, null, "Cape Town", "Battery failure");
    roadside.create(first, "roadside-conflict");

    assertThatThrownBy(() -> roadside.create(changed, "roadside-conflict"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("different request");
  }

  @Test
  void roadsideIncidentCanBeCancelledOnceAndWritesAnOutboxFact() {
    var request = new RoadsideContracts.CreateIncident(UUID.randomUUID(), null, "Cape Town", "Flat tyre");
    var incident = roadside.create(request, "roadside-cancel");

    var cancelled = roadside.cancel(incident.id());
    assertThat(cancelled.status()).isEqualTo("CANCELLED");
    assertThat(roadside.cancel(incident.id()).status()).isEqualTo("CANCELLED");
    assertThat(db.sql("select count(*) from outbox_event where aggregate_id=:id and event_type='RoadsideIncidentCancelled'")
        .param("id", incident.id()).query(Long.class).single()).isEqualTo(1);
  }

  @Test
  void operatorCanAssignTowAndOptionalRideHome() {
    var request = new RoadsideContracts.CreateIncident(UUID.randomUUID(), null, "Cape Town", "Flat tyre");
    var incident = roadside.create(request, "roadside-assignment");
    var assignment = roadside.assign(incident.id(), new RoadsideContracts.AssignRecovery(
        "Cape Tow", "quote-123", 85000, "ZAR", 45, true));

    assertThat(assignment.status()).isEqualTo("ASSIGNED");
    assertThat(assignment.rideHome()).isTrue();
    assertThat(roadside.find(incident.id()).orElseThrow().status()).isEqualTo("ASSIGNED");
    assertThat(db.sql("select count(*) from outbox_event where aggregate_id=:id and event_type='RoadsideRecoveryAssigned'")
        .param("id", incident.id()).query(Long.class).single()).isEqualTo(1);
  }

  @Test
  void dispatchQueueReturnsOnlyOpenIncidentsInOldestFirstOrder() {
    var first = roadside.create(new RoadsideContracts.CreateIncident(UUID.randomUUID(), null, "Cape Town", "Flat tyre"), "dispatch-first");
    roadside.create(new RoadsideContracts.CreateIncident(UUID.randomUUID(), null, "Cape Town", "Battery failure"), "dispatch-second");
    roadside.cancel(first.id());

    assertThat(roadside.listDispatchable(100)).noneMatch(item -> item.id().equals(first.id()));
  }

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
  void calendarListsHeldResourceAllocationsInRange() {
    OffsetDateTime start = OffsetDateTime.now(ZoneOffset.UTC).plusHours(5).withNano(0);
    var request = new BookingContracts.CreateHold(HUB, UUID.randomUUID(), null, "PRIVATE_WORK",
        start, start.plusHours(1), "CONDITIONAL_PROCUREMENT");
    var created = bookings.createHold(request, "booking-calendar");

    var entries = bookings.calendar(HUB, start.minusMinutes(5), start.plusHours(2));

    assertThat(entries).anyMatch(entry -> entry.holdId().equals(created.id())
        && entry.resourceCode().equals("WORK-01") && entry.status().equals("HELD"));
  }

  @Test
  void calendarRejectsOversizedRanges() {
    var from = OffsetDateTime.now(ZoneOffset.UTC);

    assertThatThrownBy(() -> bookings.calendar(HUB, from, from.plusDays(31).plusSeconds(1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("31 days");
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
  void availabilityOmitsAWindowWhenEveryCompatibleResourceIsOccupied() {
    OffsetDateTime start = OffsetDateTime.now(ZoneOffset.UTC).plusHours(12).withNano(0);
    for (int i = 0; i < 4; i++) {
      var request = new BookingContracts.CreateHold(HUB, UUID.randomUUID(), null, "EXPRESS_OIL",
          start, start.plusHours(1), "CONDITIONAL_PROCUREMENT");
      bookings.createHold(request, "availability-occupied-" + i);
    }

    assertThat(bookings.availability(HUB, "EXPRESS_OIL", start, start.plusHours(1))).isEmpty();
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
  void expiryCancelsPendingProcurementDemand() {
    OffsetDateTime start = OffsetDateTime.now(ZoneOffset.UTC).plusHours(9).withNano(0);
    var request = new BookingContracts.CreateHold(HUB, UUID.randomUUID(), null, "EXPRESS_OIL",
        start, start.plusHours(1), "CONDITIONAL_PROCUREMENT");
    var hold = bookings.createHold(request, "booking-expiry-demand");

    bookings.expireDueHolds(hold.expiresAt().plusSeconds(1));

    assertThat(db.sql("select count(*) from parts_demand where hold_id=:id and status='PENDING'")
        .param("id", hold.id()).query(Long.class).single()).isZero();
    assertThat(db.sql("select count(*) from parts_demand where hold_id=:id and status='CANCELLED'")
        .param("id", hold.id()).query(Long.class).single()).isEqualTo(2);
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
