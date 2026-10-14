package com.serv60.booking.internal.application;

import static org.assertj.core.api.Assertions.*;

import com.serv60.booking.api.BookingContracts.*;
import com.serv60.booking.api.CustomerBookingContracts.*;
import com.serv60.concierge.api.AssistantUnavailable;
import com.serv60.concierge.internal.application.ProposalStore;
import com.serv60.hubs.internal.application.HubDirectory;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.*;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest(
    properties = {
      "serv60.booking.expiry-sweep-enabled=false",
      "serv60.booking.express-price-minor=24900"
    })
@Import(CustomerJourneyIntegrationTest.Time.class)
@Transactional
class CustomerJourneyIntegrationTest {
  static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:18-alpine");

  static {
    PG.start();
  }

  @org.junit.jupiter.api.AfterAll
  static void stop() {
    PG.stop();
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", PG::getJdbcUrl);
    r.add("spring.datasource.username", PG::getUsername);
    r.add("spring.datasource.password", PG::getPassword);
  }

  @TestConfiguration
  static class Time {
    @Bean
    @Primary
    Clock testClock() {
      return Clock.fixed(Instant.parse("2035-01-01T06:00:00Z"), ZoneOffset.UTC);
    }
  }

  @Autowired CustomerBookingService customer;
  @Autowired BookingService bookings;
  @Autowired HubDirectory hubs;
  @Autowired ProposalStore proposals;
  @Autowired JdbcClient db;
  static final UUID HUB = UUID.fromString("00000000-0000-0000-0000-000000000060");
  final UUID owner = UUID.randomUUID();
  final OffsetDateTime start = OffsetDateTime.parse("2035-01-03T06:00:00Z");
  final AppointmentContact contact =
      new AppointmentContact("Test Customer", "+27000000000", "Toyota", "Corolla", "TEST060");

  void publish() {
    db.sql(
            "update hub set published=true,latitude=-33.925,longitude=18.424,address='Demo Cape"
                + " Town' where id=:id")
        .param("id", HUB)
        .update();
  }

  NewVisit visit(String option) {
    return new NewVisit(HUB, start, option, contact);
  }

  @Test
  void shouldDiscoverOnlyPublishedHubsIn20KmWithActualOptions() {
    assertThat(hubs.nearby(-33.925, 18.424, "")).isEmpty();
    publish();
    var found = hubs.nearby(-33.925, 18.424, "cape");
    assertThat(found).hasSize(1);
    assertThat(found.getFirst().distanceKm()).isCloseTo(0, within(0.001));
    assertThat(found.getFirst().complimentaryServices()).containsExactly("BARBER", "PRIVATE_WORK");
    assertThat(hubs.nearby(-34.2, 18.424, "")).isEmpty();
    assertThat(hubs.nearby(-33.925, 18.424, "%")).isEmpty();
    db.sql("update resource set active=false where kind='BARBER_CHAIR'").update();
    assertThat(hubs.require(HUB).complimentaryServices()).containsExactly("PRIVATE_WORK");
    assertThatThrownBy(() -> hubs.nearby(Double.NaN, 0, ""))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> hubs.nearby(0, 181, "")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> hubs.nearby(0, 0, "x".repeat(81)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> hubs.require(UUID.randomUUID()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void shouldRespectThe20KmBoundary() {
    publish();
    db.sql("update hub set latitude=:lat where id=:id")
        .param("lat", -33.925 + Math.toDegrees(19.999 / 6371.0088))
        .param("id", HUB)
        .update();
    assertThat(hubs.nearby(-33.925, 18.424, "")).hasSize(1);
    db.sql("update hub set latitude=:lat where id=:id")
        .param("lat", -33.925 + Math.toDegrees(20.001 / 6371.0088))
        .param("id", HUB)
        .update();
    assertThat(hubs.nearby(-33.925, 18.424, "")).isEmpty();
  }

  @Test
  void shouldOfferHoursReserveOnceAndScopeAppointments() {
    publish();
    assertThat(customer.slots(owner, HUB, start.toLocalDate(), "BARBER", null)).hasSize(9);
    assertThat(
            customer
                .slots(owner, HUB, start.toLocalDate(), "BARBER", null)
                .getFirst()
                .startsAt()
                .getOffset())
        .isEqualTo(ZoneOffset.ofHours(2));
    var hold = customer.create(owner, visit("BARBER"), "first");
    assertThat(customer.create(owner, visit("BARBER"), "first")).isEqualTo(hold);
    assertThat(customer.slots(owner, HUB, start.toLocalDate(), "BARBER", null)).hasSize(8);
    var appointment = customer.require(owner, hold.id());
    assertThat(appointment.contact()).isEqualTo(contact);
    assertThat(appointment.modifiable()).isFalse();
    assertThat(customer.upcoming(owner, 20, null, null).items()).hasSize(1);
    assertThat(customer.upcoming(UUID.randomUUID(), 20, null, null).items()).isEmpty();
    assertThatThrownBy(() -> customer.require(UUID.randomUUID(), hold.id()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> customer.create(owner, visit(null), "first"))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> customer.confirmPaid(owner, hold.id()))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void shouldPageExcludeExpiredAndValidateSearch() {
    publish();
    var one = customer.create(owner, visit(null), "a");
    var two = customer.create(owner, new NewVisit(HUB, start.plusHours(1), null, contact), "b");
    var page = customer.upcoming(owner, 1, null, null);
    assertThat(page.nextId()).isEqualTo(one.id());
    assertThat(
            customer.upcoming(owner, 1, page.nextStartsAt(), page.nextId()).items().getFirst().id())
        .isEqualTo(two.id());
    db.sql("update visit_hold set expires_at='2034-01-01' where id=:id")
        .param("id", one.id())
        .update();
    assertThat(customer.upcoming(owner, 20, null, null).items()).hasSize(1);
    assertThatThrownBy(() -> customer.upcoming(owner, 0, null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> customer.upcoming(owner, 51, null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> customer.upcoming(owner, 1, start, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> customer.slots(owner, HUB, start.toLocalDate().minusDays(4), null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> customer.slots(owner, HUB, start.toLocalDate().plusDays(91), null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> customer.slots(owner, HUB, start.toLocalDate(), "MASSAGE", null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> customer.create(owner, visit(null), " "))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                customer.create(
                    owner, new NewVisit(HUB, start.plusMinutes(15), null, contact), "bad"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void shouldAtomicallyRescheduleWithRevisionReceiptAndRetainParts() {
    publish();
    var hold = customer.create(owner, visit("BARBER"), "change");
    bookings.confirm(hold.id());
    var old = customer.require(owner, hold.id());
    assertThat(old.modifiable()).isTrue();
    var request = new ChangeVisit(start.plusDays(1), "PRIVATE_WORK", 0);
    var changed = customer.change(owner, hold.id(), request, "update-1");
    assertThat(changed.revision()).isEqualTo(1);
    assertThat(changed.startsAt()).isEqualTo(start.plusDays(1));
    assertThat(customer.change(owner, hold.id(), request, "update-1")).isEqualTo(changed);
    assertThat(customer.confirmPaid(owner, hold.id())).isEqualTo(changed);
    assertThat(
            db.sql(
                    "select count(*) from resource_allocation where hold_id=:id and"
                        + " status='CONFIRMED'")
                .param("id", hold.id())
                .query(Integer.class)
                .single())
        .isEqualTo(2);
    assertThat(
            db.sql("select count(*) from stock_reservation where hold_id=:id")
                .param("id", hold.id())
                .query(Integer.class)
                .single())
        .isEqualTo(2);
    assertThatThrownBy(() -> customer.change(owner, hold.id(), request, "update-2"))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () -> customer.change(owner, hold.id(), new ChangeVisit(start, null, 1), "update-1"))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> customer.change(UUID.randomUUID(), hold.id(), request, "x"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void shouldKeepOriginalSlotWhenReplacementIsFullOrInsideCutoff() {
    publish();
    var hold = customer.create(owner, visit(null), "own");
    bookings.confirm(hold.id());
    customer.create(
        UUID.randomUUID(), new NewVisit(HUB, start.plusDays(1), "BARBER", contact), "other");
    assertThatThrownBy(
            () ->
                customer.change(
                    owner, hold.id(), new ChangeVisit(start.plusDays(1), "BARBER", 0), "full"))
        .isInstanceOf(IllegalStateException.class);
    assertThat(customer.require(owner, hold.id()).startsAt()).isEqualTo(start);
    assertThatThrownBy(
            () ->
                customer.change(
                    owner, hold.id(), new ChangeVisit(start.minusDays(1), null, 0), "early"))
        .isInstanceOf(IllegalArgumentException.class);
    db.sql("update visit_hold set starts_at=:start where id=:id")
        .param("start", start.minusMinutes(1))
        .param("id", hold.id())
        .update();
    assertThatThrownBy(
            () ->
                customer.change(
                    owner, hold.id(), new ChangeVisit(start.plusDays(1), null, 0), "late"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void shouldConfirmProposalOnceAndKeepOwnershipExpiryAndQuota() {
    publish();
    var proposal = proposals.booking(owner, hubs.require(HUB), visit(null));
    assertThat(customer.upcoming(owner, 20, null, null).items()).isEmpty();
    assertThatThrownBy(() -> proposals.confirm(UUID.randomUUID(), proposal.id()))
        .isInstanceOf(IllegalArgumentException.class);
    var held = proposals.confirm(owner, proposal.id());
    assertThat(held.status()).isEqualTo("HELD");
    assertThat(proposals.confirm(owner, proposal.id())).isEqualTo(held);
    bookings.confirm(held.id());
    var change =
        proposals.change(
            owner,
            hubs.require(HUB),
            customer.require(owner, held.id()),
            new ChangeVisit(start.plusDays(1), null, 0));
    assertThat(proposals.confirm(owner, change.id()).startsAt()).isEqualTo(start.plusDays(1));
    var expired = proposals.booking(owner, hubs.require(HUB), visit("BARBER"));
    db.sql("update assistant_proposal set expires_at='2034-01-01' where id=:id")
        .param("id", expired.id())
        .update();
    assertThatThrownBy(() -> proposals.confirm(owner, expired.id()))
        .isInstanceOf(IllegalStateException.class);
    for (int i = 0; i < 30; i++) proposals.consumeQuota(owner);
    assertThatThrownBy(() -> proposals.consumeQuota(owner))
        .isInstanceOf(AssistantUnavailable.class);
    assertThat(
            db.sql("select count(*) from assistant_proposal where id=:id")
                .param("id", expired.id())
                .query(Integer.class)
                .single())
        .isZero();
  }

  @Test
  void shouldPriceOnServerAndConfirmOnlyOwnedPaidHold() {
    publish();
    var hold = customer.create(owner, visit(null), "pay");
    var payment = customer.payment(owner, hold.id());
    assertThat(payment.amountMinor()).isEqualTo(24900);
    assertThat(customer.payment(owner, hold.id())).isEqualTo(payment);
    db.sql("update payment_order set status='PAID' where id=:id")
        .param("id", payment.id())
        .update();
    db.sql("update payment_order set amount_minor=1 where id=:id")
        .param("id", payment.id())
        .update();
    assertThatThrownBy(() -> customer.confirmPaid(owner, hold.id()))
        .isInstanceOf(IllegalStateException.class);
    db.sql("update payment_order set amount_minor=24900 where id=:id")
        .param("id", payment.id())
        .update();
    assertThat(customer.confirmPaid(owner, hold.id()).status()).isEqualTo("CONFIRMED");
    assertThatThrownBy(() -> customer.payment(owner, hold.id()))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void shouldCancelOwnedHoldAndConfirmedVisitWithNotice() {
    publish();
    var held = customer.create(owner, visit(null), "cancel-hold");
    assertThat(customer.cancel(owner, held.id()).status()).isEqualTo("CANCELLED");
    assertThat(customer.cancel(owner, held.id()).status()).isEqualTo("CANCELLED");
    var confirmed = customer.create(owner, new NewVisit(HUB, start.plusDays(7), null, contact), "cancel-ok");
    bookings.confirm(confirmed.id());
    assertThat(customer.require(owner, confirmed.id()).cancellable()).isTrue();
    assertThat(customer.cancel(owner, confirmed.id()).status()).isEqualTo("CANCELLED");
    assertThatThrownBy(() -> customer.cancel(UUID.randomUUID(), confirmed.id()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
  void shouldAllowExactlyOneConcurrentChangeAtTheSameRevision() throws Exception {
    publish();
    var hold = customer.create(owner, new NewVisit(HUB, start.plusDays(30), null, contact), "race");
    bookings.confirm(hold.id());
    try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
      var gate = new java.util.concurrent.CountDownLatch(1);
      java.util.concurrent.Callable<Boolean> first =
          () -> {
            gate.await();
            try {
              customer.change(
                  owner, hold.id(), new ChangeVisit(start.plusDays(31), null, 0), "race-a");
              return true;
            } catch (IllegalStateException stale) {
              return false;
            }
          };
      java.util.concurrent.Callable<Boolean> second =
          () -> {
            gate.await();
            try {
              customer.change(
                  owner, hold.id(), new ChangeVisit(start.plusDays(32), null, 0), "race-b");
              return true;
            } catch (IllegalStateException stale) {
              return false;
            }
          };
      var a = executor.submit(first);
      var b = executor.submit(second);
      gate.countDown();
      assertThat(
              java.util.List.of(
                  a.get(10, java.util.concurrent.TimeUnit.SECONDS),
                  b.get(10, java.util.concurrent.TimeUnit.SECONDS)))
          .containsExactlyInAnyOrder(true, false);
      assertThat(customer.require(owner, hold.id()).revision()).isEqualTo(1);
      assertThat(
              db.sql(
                      "select count(*) from outbox_event where aggregate_id=:id and"
                          + " event_type='VisitRescheduled'")
                  .param("id", hold.id())
                  .query(Integer.class)
                  .single())
          .isEqualTo(1);
    } finally {
      bookings.cancel(hold.id());
      db.sql("update hub set published=false where id=:id").param("id", HUB).update();
    }
  }

  @Test
  void shouldRejectExpiredCheckoutAndCustomerPayment() {
    publish();
    var hold = customer.create(owner, visit(null), "expire-payment");
    var payment = customer.payment(owner, hold.id());
    db.sql("update visit_hold set expires_at='2034-01-01' where id=:id")
        .param("id", hold.id())
        .update();
    assertThatThrownBy(() -> customer.payment(owner, hold.id()))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> paymentService.requirePayable(payment))
        .isInstanceOf(IllegalStateException.class);
  }

  @Autowired com.serv60.payment.internal.application.PaymentService paymentService;

  com.serv60.payment.api.PaymentContracts.ProviderPaymentEvent settled(
      com.serv60.payment.api.PaymentContracts.PaymentOrder order, String eventId) {
    return new com.serv60.payment.api.PaymentContracts.ProviderPaymentEvent(
        "YOCO", eventId, order.id(), "ch_" + eventId, order.amountMinor(), "ZAR", "PAID");
  }

  int reconciliationEvents(UUID hold) {
    return db.sql(
            "select count(*) from outbox_event where aggregate_id=:id and"
                + " event_type='PaidVisitNeedsReconciliation'")
        .param("id", hold)
        .query(Integer.class)
        .single();
  }

  @Test
  void shouldConfirmHoldWhenProviderSettlesPaymentEvenPastDisplayedExpiry() {
    publish();
    var hold = customer.create(owner, visit(null), "settle");
    var payment = customer.payment(owner, hold.id());
    db.sql("update visit_hold set expires_at='2034-01-01' where id=:id")
        .param("id", hold.id())
        .update();

    paymentService.reconcile(settled(payment, "evt-settle"), "{}");
    paymentService.reconcile(settled(payment, "evt-settle"), "{}");

    assertThat(customer.require(owner, hold.id()).status()).isEqualTo("CONFIRMED");
    assertThat(reconciliationEvents(hold.id())).isZero();
  }

  @Test
  void shouldFlagSettledPaymentForReconciliationWhenHoldWasAlreadyReleased() {
    publish();
    var hold = customer.create(owner, visit(null), "late-pay");
    var payment = customer.payment(owner, hold.id());
    bookings.expireDueHolds(hold.expiresAt().plusSeconds(1));

    var order = paymentService.reconcile(settled(payment, "evt-late"), "{}");

    assertThat(order.status()).isEqualTo("PAID");
    assertThat(customer.require(owner, hold.id()).status()).isEqualTo("EXPIRED");
    assertThat(reconciliationEvents(hold.id())).isEqualTo(1);
    assertThatThrownBy(() -> customer.confirmPaid(owner, hold.id()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("released");
  }

  @Test
  void shouldNeverConfirmMispricedPaymentAndShouldAcceptOperatorConfirmedHold() {
    publish();
    var hold = customer.create(owner, visit(null), "misprice");
    var cheap =
        paymentService.create(
            new com.serv60.payment.api.PaymentContracts.CreatePayment(
                owner, hold.id(), 100, "ZAR", "YOCO"),
            "cheap");
    paymentService.reconcile(settled(cheap, "evt-cheap"), "{}");
    assertThat(customer.require(owner, hold.id()).status()).isEqualTo("HELD");
    assertThat(reconciliationEvents(hold.id())).isEqualTo(1);

    bookings.confirm(hold.id());
    var fair =
        paymentService.create(
            new com.serv60.payment.api.PaymentContracts.CreatePayment(
                owner, hold.id(), 24900, "ZAR", "YOCO"),
            "fair");
    paymentService.reconcile(settled(fair, "evt-fair"), "{}");

    assertThat(customer.require(owner, hold.id()).status()).isEqualTo("CONFIRMED");
    assertThat(reconciliationEvents(hold.id())).isEqualTo(1);
  }

  @Test
  void shouldReserveMassageAndEvaluateEachRequiredPart() {
    publish();
    db.sql(
            "insert into service_template(id,hub_id,code,name,duration_minutes) values"
                + " (:id,:hub,'MASSAGE','Massage',45)")
        .param("id", UUID.randomUUID())
        .param("hub", HUB)
        .update();
    db.sql(
            "insert into resource(id,hub_id,code,kind) values"
                + " (:id,:hub,'MASSAGE-01','MASSAGE_ROOM')")
        .param("id", UUID.randomUUID())
        .param("hub", HUB)
        .update();
    var ready = customer.create(owner, visit("MASSAGE"), "massage");
    assertThat(ready.policy()).isEqualTo("READY_STOCK");
    assertThat(bookings.calendar(HUB, start, start.plusHours(1)))
        .anySatisfy(
            entry -> {
              assertThat(entry.serviceCode()).isEqualTo("MASSAGE");
              assertThat(entry.endsAt()).isEqualTo(start.plusMinutes(45));
            });
    // One filter remains reserved. Abundant oil must not mask the filter shortage.
    db.sql(
            "update stock_balance set usable_quantity=reserved_quantity where hub_id=:hub and"
                + " sku='FILTER-OIL-001'")
        .param("hub", HUB)
        .update();
    assertThat(customer.create(owner, visit("MASSAGE"), "massage")).isEqualTo(ready);
    var conditional =
        customer.create(owner, new NewVisit(HUB, start.plusHours(1), null, contact), "shortage");
    assertThat(conditional.policy()).isEqualTo("CONDITIONAL_PROCUREMENT");
    assertThat(
            db.sql("select count(*) from parts_demand where hold_id=:id")
                .param("id", conditional.id())
                .query(Integer.class)
                .single())
        .isEqualTo(2);
  }
}
