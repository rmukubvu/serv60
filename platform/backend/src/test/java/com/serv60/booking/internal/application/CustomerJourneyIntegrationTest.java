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
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import com.serv60.support.PlatformIntegrationTest;

@PlatformIntegrationTest
@Transactional
class CustomerJourneyIntegrationTest {
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
  void shouldHideAnUnpublishedHubFromDiscovery() {
    var found = hubs.nearby(-33.925, 18.424, "");

    assertThat(found).isEmpty();
  }

  @Test
  void shouldFindAPublishedHubByNameWithItsActualOptions() {
    publish();

    var found = hubs.nearby(-33.925, 18.424, "cape");

    assertThat(found).singleElement().satisfies(hub -> {
      assertThat(hub.distanceKm()).isCloseTo(0, within(0.001));
      assertThat(hub.complimentaryServices()).containsExactly("BARBER", "PRIVATE_WORK");
    });
  }

  @Test
  void shouldTreatSearchWildcardsAsPlainText() {
    publish();

    var found = hubs.nearby(-33.925, 18.424, "%");

    assertThat(found).isEmpty();
  }

  @Test
  void shouldDropAComplimentaryOptionThatHasNoActiveResource() {
    publish();
    db.sql("update resource set active=false where kind='BARBER_CHAIR'").update();

    var options = hubs.require(HUB).complimentaryServices();

    assertThat(options).containsExactly("PRIVATE_WORK");
  }

  @ParameterizedTest(name = "lat={0} lon={1} query length={2}")
  @CsvSource({"NaN,0,0", "0,181,0", "0,0,81"})
  void shouldRejectAnInvalidDiscoverySearch(double latitude, double longitude, int queryLength) {
    assertThatThrownBy(() -> hubs.nearby(latitude, longitude, "x".repeat(queryLength)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void shouldReportAnUnknownHubAsNotFound() {
    assertThatThrownBy(() -> hubs.require(UUID.randomUUID()))
        .isInstanceOf(com.serv60.errors.NotFound.class);
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

  Hold reserve(String option, String key) {
    publish();
    return customer.create(owner, visit(option), key);
  }

  @Test
  void shouldOfferOneSlotPerTradingHourThatFitsTheOption() {
    publish();

    var slots = customer.slots(owner, HUB, start.toLocalDate(), "BARBER", null);

    assertThat(slots).hasSize(9);
  }

  @Test
  void shouldExpressSlotsInTheHubsLocalOffset() {
    publish();

    var first = customer.slots(owner, HUB, start.toLocalDate(), "BARBER", null).getFirst();

    assertThat(first.startsAt().getOffset()).isEqualTo(ZoneOffset.ofHours(2));
  }

  @Test
  void shouldReplayAReservationForTheSameKey() {
    var hold = reserve("BARBER", "first");

    var replay = customer.create(owner, visit("BARBER"), "first");

    assertThat(replay).isEqualTo(hold);
  }

  @Test
  void shouldRemoveTheReservedSlotFromAvailability() {
    reserve("BARBER", "first");

    var slots = customer.slots(owner, HUB, start.toLocalDate(), "BARBER", null);

    assertThat(slots).hasSize(8);
  }

  @Test
  void shouldKeepTheContactGivenAtReservation() {
    var hold = reserve("BARBER", "first");

    var appointment = customer.require(owner, hold.id());

    assertThat(appointment.contact()).isEqualTo(contact);
  }

  @Test
  void shouldNotOfferChangesOnAnUnpaidHold() {
    var hold = reserve("BARBER", "first");

    var appointment = customer.require(owner, hold.id());

    assertThat(appointment.modifiable()).isFalse();
  }

  @Test
  void shouldListTheReservationForItsOwner() {
    reserve("BARBER", "first");

    var upcoming = customer.upcoming(owner, 20, null, null).items();

    assertThat(upcoming).hasSize(1);
  }

  @Test
  void shouldNotListAnotherCustomersReservation() {
    reserve("BARBER", "first");

    var upcoming = customer.upcoming(UUID.randomUUID(), 20, null, null).items();

    assertThat(upcoming).isEmpty();
  }

  @Test
  void shouldNotReadAnotherCustomersReservation() {
    var hold = reserve("BARBER", "first");

    assertThatThrownBy(() -> customer.require(UUID.randomUUID(), hold.id()))
        .isInstanceOf(com.serv60.errors.NotFound.class);
  }

  @Test
  void shouldRejectAKeyReusedForADifferentVisit() {
    reserve("BARBER", "first");

    assertThatThrownBy(() -> customer.create(owner, visit(null), "first"))
        .isInstanceOf(com.serv60.errors.Conflict.class);
  }

  @Test
  void shouldRefuseToConfirmAnUnpaidReservation() {
    var hold = reserve("BARBER", "first");

    assertThatThrownBy(() -> customer.confirmPaid(owner, hold.id()))
        .isInstanceOf(com.serv60.errors.Conflict.class);
  }

  @Test
  void shouldPageUpcomingAppointmentsByStartThenId() {
    publish();
    var one = customer.create(owner, visit(null), "a");
    var two = customer.create(owner, new NewVisit(HUB, start.plusHours(1), null, contact), "b");

    var first = customer.upcoming(owner, 1, null, null);
    var second = customer.upcoming(owner, 1, first.nextStartsAt(), first.nextId());

    assertThat(List.of(first.nextId(), second.items().getFirst().id())).containsExactly(one.id(), two.id());
  }

  @Test
  void shouldExcludeAnExpiredHoldFromUpcoming() {
    publish();
    var expired = customer.create(owner, visit(null), "a");
    customer.create(owner, new NewVisit(HUB, start.plusHours(1), null, contact), "b");
    db.sql("update visit_hold set expires_at='2034-01-01' where id=:id").param("id", expired.id()).update();

    var upcoming = customer.upcoming(owner, 20, null, null).items();

    assertThat(upcoming).extracting(Appointment::id).doesNotContain(expired.id()).hasSize(1);
  }

  @ParameterizedTest(name = "limit={0} after given={1} afterId given={2}")
  @CsvSource({"0,false,false", "51,false,false", "1,true,false"})
  void shouldRejectAnInvalidPage(int limit, boolean withAfter, boolean withAfterId) {
    var after = withAfter ? start : null;
    var afterId = withAfterId ? UUID.randomUUID() : null;

    assertThatThrownBy(() -> customer.upcoming(owner, limit, after, afterId))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest(name = "{0} days from the visit date, option {1}")
  @CsvSource(value = {"-4,NONE", "91,NONE", "0,MASSAGE"})
  void shouldRejectASlotSearchOutsideTheHorizonOrForAnOptionNotOffered(int days, String option) {
    publish();
    var date = start.toLocalDate().plusDays(days);
    var chosen = option.equals("NONE") ? null : option;

    assertThatThrownBy(() -> customer.slots(owner, HUB, date, chosen, null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void shouldRejectABlankIdempotencyKey() {
    publish();

    assertThatThrownBy(() -> customer.create(owner, visit(null), " "))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void shouldRejectAStartThatIsNotAnOfferedSlot() {
    publish();

    assertThatThrownBy(() -> customer.create(owner, new NewVisit(HUB, start.plusMinutes(15), null, contact), "bad"))
        .isInstanceOf(com.serv60.errors.Conflict.class);
  }

  final ChangeVisit nextDay = new ChangeVisit(start.plusDays(1), "PRIVATE_WORK", 0);

  Hold confirmedVisit() {
    publish();
    var hold = customer.create(owner, visit("BARBER"), "change");
    bookings.confirm(hold.id(), "test");
    return hold;
  }

  int countFor(String table, UUID hold, String extra) {
    return db.sql("select count(*) from " + table + " where hold_id=:id" + extra)
        .param("id", hold).query(Integer.class).single();
  }

  @Test
  void shouldOpenAConfirmedVisitForChanges() {
    var hold = confirmedVisit();

    assertThat(customer.require(owner, hold.id()).modifiable()).isTrue();
  }

  @Test
  void shouldMoveTheVisitAndBumpItsRevision() {
    var hold = confirmedVisit();

    var changed = customer.change(owner, hold.id(), nextDay, "update-1");

    assertThat(changed).extracting(Appointment::startsAt, Appointment::revision)
        .containsExactly(start.plusDays(1), 1L);
  }

  @Test
  void shouldReplayAChangeForTheSameKey() {
    var hold = confirmedVisit();
    var changed = customer.change(owner, hold.id(), nextDay, "update-1");

    var replay = customer.change(owner, hold.id(), nextDay, "update-1");

    assertThat(replay).isEqualTo(changed);
  }

  @Test
  void shouldTreatConfirmPaymentAsAReadOnAConfirmedVisit() {
    var hold = confirmedVisit();
    var changed = customer.change(owner, hold.id(), nextDay, "update-1");

    var reread = customer.confirmPaid(owner, hold.id());

    assertThat(reread).isEqualTo(changed);
  }

  @Test
  void shouldMoveBothAllocationsToTheNewTime() {
    var hold = confirmedVisit();

    customer.change(owner, hold.id(), nextDay, "update-1");

    assertThat(countFor("resource_allocation", hold.id(), " and status='CONFIRMED'")).isEqualTo(2);
  }

  @Test
  void shouldKeepThePartsReservedAcrossAChange() {
    var hold = confirmedVisit();

    customer.change(owner, hold.id(), nextDay, "update-1");

    assertThat(countFor("stock_reservation", hold.id(), "")).isEqualTo(2);
  }

  @Test
  void shouldRejectAChangeAtAStaleRevision() {
    var hold = confirmedVisit();
    customer.change(owner, hold.id(), nextDay, "update-1");

    assertThatThrownBy(() -> customer.change(owner, hold.id(), nextDay, "update-2"))
        .isInstanceOf(com.serv60.errors.Conflict.class);
  }

  @Test
  void shouldRejectAKeyReusedForADifferentChange() {
    var hold = confirmedVisit();
    customer.change(owner, hold.id(), nextDay, "update-1");

    assertThatThrownBy(() -> customer.change(owner, hold.id(), new ChangeVisit(start, null, 1), "update-1"))
        .isInstanceOf(com.serv60.errors.Conflict.class);
  }

  @Test
  void shouldNotLetAnotherCustomerChangeTheVisit() {
    var hold = confirmedVisit();

    assertThatThrownBy(() -> customer.change(UUID.randomUUID(), hold.id(), nextDay, "x"))
        .isInstanceOf(com.serv60.errors.NotFound.class);
  }

  @Test
  void shouldKeepOriginalSlotWhenReplacementIsFullOrInsideCutoff() {
    publish();
    var hold = customer.create(owner, visit(null), "own");
    bookings.confirm(hold.id(), "test");
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

  com.serv60.concierge.api.ConciergeContracts.Proposal bookingProposal() {
    publish();
    return proposals.booking(owner, hubs.require(HUB), visit(null));
  }

  @Test
  void shouldReserveNothingUntilAProposalIsConfirmed() {
    bookingProposal();

    assertThat(customer.upcoming(owner, 20, null, null).items()).isEmpty();
  }

  @Test
  void shouldNotConfirmAnotherCustomersProposal() {
    var proposal = bookingProposal();

    assertThatThrownBy(() -> proposals.confirm(UUID.randomUUID(), proposal.id()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void shouldHoldTheVisitWhenABookingProposalIsConfirmed() {
    var proposal = bookingProposal();

    var held = proposals.confirm(owner, proposal.id());

    assertThat(held.status()).isEqualTo("HELD");
  }

  @Test
  void shouldConfirmAProposalOnlyOnce() {
    var proposal = bookingProposal();
    var held = proposals.confirm(owner, proposal.id());

    var replay = proposals.confirm(owner, proposal.id());

    assertThat(replay).isEqualTo(held);
  }

  @Test
  void shouldApplyAConfirmedChangeProposal() {
    var held = proposals.confirm(owner, bookingProposal().id());
    bookings.confirm(held.id(), "test");
    var change = proposals.change(owner, hubs.require(HUB), customer.require(owner, held.id()),
        new ChangeVisit(start.plusDays(1), null, 0));

    var moved = proposals.confirm(owner, change.id());

    assertThat(moved.startsAt()).isEqualTo(start.plusDays(1));
  }

  @Test
  void shouldRefuseAnExpiredProposal() {
    var expired = bookingProposal();
    db.sql("update assistant_proposal set expires_at='2034-01-01' where id=:id").param("id", expired.id()).update();

    assertThatThrownBy(() -> proposals.confirm(owner, expired.id()))
        .isInstanceOf(com.serv60.errors.Conflict.class);
  }

  @Test
  void shouldLimitAssistantTurnsToThirtyPerHour() {
    for (int i = 0; i < 30; i++) proposals.consumeQuota(owner);

    assertThatThrownBy(() -> proposals.consumeQuota(owner)).isInstanceOf(AssistantUnavailable.class);
  }

  @Test
  void shouldPurgeLongExpiredProposalsWhileConsumingQuota() {
    var expired = bookingProposal();
    db.sql("update assistant_proposal set expires_at='2034-01-01' where id=:id").param("id", expired.id()).update();

    proposals.consumeQuota(owner);

    assertThat(db.sql("select count(*) from assistant_proposal where id=:id")
        .param("id", expired.id()).query(Integer.class).single()).isZero();
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
    bookings.confirm(confirmed.id(), "test");
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
    bookings.confirm(hold.id(), "test");
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

  int refundRequests(UUID hold) {
    return db.sql(
            "select count(*) from outbox_event where aggregate_id=:id and event_type='RefundRequired'")
        .param("id", hold)
        .query(Integer.class)
        .single();
  }

  @Test
  void shouldRequestARefundWhenAPaidVisitIsCancelledButNotWhenUnpaid() {
    publish();
    var paid = customer.create(owner, new NewVisit(HUB, start.plusDays(7), null, contact), "refund-paid");
    paymentService.reconcile(settled(customer.payment(owner, paid.id()), "evt-refund"), "{}");
    var unpaid = customer.create(owner, new NewVisit(HUB, start.plusDays(8), null, contact), "refund-unpaid");

    customer.cancel(owner, paid.id());
    customer.cancel(owner, unpaid.id());

    assertThat(refundRequests(paid.id())).isEqualTo(1);
    assertThat(refundRequests(unpaid.id())).isZero();
  }

  @Test
  void shouldHoldForTheConfiguredDuration() {
    publish();

    var hold = customer.create(owner, visit(null), "duration");

    assertThat(hold.expiresAt()).isEqualTo(OffsetDateTime.parse("2035-01-01T06:10:00Z"));
  }

  @Test
  void shouldRejectHoldDurationsOutsideTheSupportedRange() {
    for (long seconds : List.of(59L, 3601L))
      assertThatThrownBy(() -> new BookingService(null, null, null, null, seconds, null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("hold-duration-seconds");
  }

  @Autowired com.serv60.payment.internal.application.YocoWebhookParser yocoParser;

  OffsetDateTime expiresAt(UUID hold) {
    return db.sql("select expires_at from visit_hold where id=:id").param("id", hold)
        .query(OffsetDateTime.class).single();
  }

  com.serv60.payment.api.PaymentContracts.ProviderPaymentEvent outcome(
      com.serv60.payment.api.PaymentContracts.PaymentOrder order, String eventId, String status) {
    return new com.serv60.payment.api.PaymentContracts.ProviderPaymentEvent(
        "YOCO", eventId, order.id(), "ch_" + eventId, order.amountMinor(), "ZAR", status);
  }

  @Test
  void shouldExtendTheHoldOnceWhenThePaymentStarts() {
    publish();
    var hold = customer.create(owner, visit(null), "extend");

    customer.payment(owner, hold.id());

    assertThat(expiresAt(hold.id())).isEqualTo(OffsetDateTime.parse("2035-01-01T06:20:00Z"));
  }

  @Test
  void shouldNotExtendTheHoldASecondTime() {
    publish();
    var hold = customer.create(owner, visit(null), "extend-once");
    customer.payment(owner, hold.id());
    db.sql("update visit_hold set expires_at='2035-01-01T06:12:00Z' where id=:id").param("id", hold.id()).update();

    customer.payment(owner, hold.id());

    assertThat(expiresAt(hold.id())).isEqualTo(OffsetDateTime.parse("2035-01-01T06:12:00Z"));
  }

  @Test
  void shouldLetTheCustomerPayAgainAfterAFailedAttempt() {
    publish();
    var hold = customer.create(owner, visit(null), "retry");
    var failed = customer.payment(owner, hold.id());
    paymentService.reconcile(outcome(failed, "evt-failed", "FAILED"), "{}");

    var retry = customer.payment(owner, hold.id());

    assertThat(retry.id()).isNotEqualTo(failed.id());
    assertThat(retry.status()).isEqualTo("PENDING");
  }

  @Test
  void shouldRefuseASecondOrderWhileOneIsPending() {
    publish();
    var hold = customer.create(owner, visit(null), "pending");
    customer.payment(owner, hold.id());

    assertThatThrownBy(() -> paymentService.create(
            new com.serv60.payment.api.PaymentContracts.CreatePayment(owner, hold.id(), 24900, "ZAR", "YOCO"),
            "operator-second"))
        .isInstanceOf(com.serv60.errors.Conflict.class)
        .hasMessageContaining("in progress");
  }

  @Test
  void shouldFlagASecondPaidOrderOnAConfirmedVisitForReconciliation() {
    publish();
    var hold = customer.create(owner, visit(null), "duplicate");
    var first = customer.payment(owner, hold.id());
    paymentService.reconcile(settled(first, "evt-first"), "{}");
    // A second order created before the one-at-a-time guard existed, now also paid.
    var second = UUID.randomUUID();
    db.sql("""
        insert into payment_order(id,customer_id,hold_id,amount_minor,currency,provider,status,
          idempotency_key,request_hash,created_at,updated_at)
        values (:id,:customer,:hold,24900,'ZAR','YOCO','PENDING','legacy-second','x',now(),now())
        """).param("id", second).param("customer", owner).param("hold", hold.id()).update();

    paymentService.reconcile(new com.serv60.payment.api.PaymentContracts.ProviderPaymentEvent(
        "YOCO", "evt-second", second, "ch_second", 24900, "ZAR", "PAID"), "{}");

    assertThat(db.sql("""
        select count(*) from outbox_event where aggregate_id=:id
           and event_type='PaidVisitNeedsReconciliation' and payload->>'reason'='duplicate_payment'
        """).param("id", hold.id()).query(Integer.class).single()).isEqualTo(1);
  }

  @Test
  void shouldConfirmAVisitFromAWebhookInYocosDocumentedShape() {
    publish();
    var hold = customer.create(owner, visit(null), "yoco-shape");
    var order = customer.payment(owner, hold.id());
    var body = """
        {"id":"evt_live","type":"payment.succeeded","createdDate":"2035-01-01T06:05:00Z",
         "payload":{"id":"p_1","type":"payment","status":"succeeded","amount":24900,"currency":"ZAR",
                    "metadata":{"checkoutId":"ch_live","paymentOrderId":"%s"}}}
        """.formatted(order.id());

    paymentService.reconcile(yocoParser.parse("evt_live", body).orElseThrow(), body);

    assertThat(customer.require(owner, hold.id()).status()).isEqualTo("CONFIRMED");
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
    assertThat(
            db.sql("select confirmed_by from visit_hold where id=:id")
                .param("id", hold.id())
                .query(String.class)
                .single())
        .isEqualTo("payment:" + payment.id());
    assertThat(
            db.sql(
                    "select count(*) from outbox_event where aggregate_id=:id and"
                        + " event_type='VisitConfirmed'")
                .param("id", hold.id())
                .query(Integer.class)
                .single())
        .isEqualTo(1);
  }

  @Test
  void shouldFlagSettledPaymentForReconciliationWhenHoldWasAlreadyReleased() {
    publish();
    var hold = customer.create(owner, visit(null), "late-pay");
    var payment = customer.payment(owner, hold.id());
    // Payment extended the hold; the sweep releases it once even that has passed.
    bookings.expireDueHolds(expiresAt(hold.id()).plusSeconds(1));

    var order = paymentService.reconcile(settled(payment, "evt-late"), "{}");

    assertThat(order.status()).isEqualTo("PAID");
    assertThat(customer.require(owner, hold.id()).status()).isEqualTo("EXPIRED");
    assertThat(reconciliationEvents(hold.id())).isEqualTo(1);
    assertThatThrownBy(() -> customer.confirmPaid(owner, hold.id()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("released");
  }

  @Test
  void shouldNeverConfirmMispricedPaymentNorTakeASecondPayment() {
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
    assertThatThrownBy(
            () ->
                paymentService.create(
                    new com.serv60.payment.api.PaymentContracts.CreatePayment(
                        owner, hold.id(), 24900, "ZAR", "YOCO"),
                    "second-charge"))
        .isInstanceOf(com.serv60.errors.Conflict.class)
        .hasMessageContaining("already paid");
  }

  @Test
  void shouldAcceptPaymentForAnOperatorConfirmedHoldWithoutReconfirming() {
    publish();
    var hold = customer.create(owner, visit(null), "operator-confirmed");
    bookings.confirm(hold.id(), "test");
    var fair =
        paymentService.create(
            new com.serv60.payment.api.PaymentContracts.CreatePayment(
                owner, hold.id(), 24900, "ZAR", "YOCO"),
            "fair");

    paymentService.reconcile(settled(fair, "evt-fair"), "{}");

    assertThat(customer.require(owner, hold.id()).status()).isEqualTo("CONFIRMED");
    assertThat(reconciliationEvents(hold.id())).isZero();
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
