package com.serv60.hubs.internal.application;

import static org.assertj.core.api.Assertions.*;

import com.serv60.booking.api.BookingContracts.*;
import com.serv60.booking.internal.application.BookingService;
import com.serv60.booking.internal.application.CustomerBookingService;
import com.serv60.hubs.api.HubSettingsContracts.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.*;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest(
    properties = {"serv60.booking.expiry-sweep-enabled=false", "serv60.security.enabled=false"})
@Import(HubSettingsIntegrationTest.Time.class)
class HubSettingsIntegrationTest {
  static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:18-alpine");

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

  @TestConfiguration
  static class Time {
    @Bean
    @Primary
    Clock settingsTestClock() {
      return Clock.fixed(Instant.parse("2035-01-01T06:00:00Z"), ZoneOffset.UTC);
    }
  }

  @Autowired HubSettingsService settings;
  @Autowired TradingSchedule schedule;
  @Autowired HubDirectory directory;
  @Autowired BookingService bookings;
  @Autowired CustomerBookingService customers;
  @Autowired JdbcClient db;
  @Autowired PlatformTransactionManager transactions;
  UUID hub;
  final OffsetDateTime start = OffsetDateTime.parse("2035-01-03T08:00:00+02:00");

  @BeforeEach
  void provision() {
    hub = UUID.randomUUID();
    db.sql(
            "insert into hub(id,name,timezone,latitude,longitude,address,published) values"
                + " (:hub,'Test hub','Africa/Johannesburg',-33.925,18.424,'Test location',true)")
        .param("hub", hub)
        .update();
    db.sql("insert into resource(id,hub_id,code,kind) values (:id,:hub,'EXPRESS-01','EXPRESS_BAY')")
        .param("hub", hub)
        .param("id", UUID.randomUUID())
        .update();
    db.sql(
            "insert into service_template(id,hub_id,code,name,duration_minutes) values"
                + " (:id,:hub,'EXPRESS_OIL','Express',60)")
        .param("hub", hub)
        .param("id", UUID.randomUUID())
        .update();
  }

  Update change(Capacity capacity) {
    var current = settings.read(hub);
    return new Update(current.revision(), capacity, current.tradingHours());
  }

  Hold reserve(String option, OffsetDateTime at) {
    return bookings.createHold(
        new CreateHold(
            hub,
            UUID.randomUUID(),
            null,
            "EXPRESS_OIL",
            at,
            at.plusHours(1),
            "READY_STOCK",
            option),
        UUID.randomUUID().toString());
  }

  @Test
  void shouldProvisionReuseAndPublishRealCapacityWithoutDuplicateEvents() {
    var before = settings.read(hub);
    assertThat(before.tradingHours()).hasSize(7);
    assertThat(before.capacity()).isEqualTo(new Capacity(1, 0, 0, 0));
    var update = change(new Capacity(3, 2, 1, 2));
    var saved = settings.update(hub, update);
    assertThat(saved.resources()).hasSize(8);
    assertThat(saved.revision()).isEqualTo(1);
    assertThat(directory.require(hub).complimentaryServices())
        .containsExactly("BARBER", "MASSAGE", "PRIVATE_WORK");
    assertThat(settings.update(hub, update)).isEqualTo(saved);
    assertThat(
            db.sql("select count(*) from outbox_event where aggregate_id=:hub")
                .param("hub", hub)
                .query(Integer.class)
                .single())
        .isEqualTo(1);
    var ids = saved.resources().stream().map(Resource::id).toList();
    settings.update(hub, change(new Capacity(1, 0, 0, 0)));
    assertThat(directory.require(hub).complimentaryServices()).isEmpty();
    var restored = settings.update(hub, change(new Capacity(3, 2, 1, 2)));
    assertThat(restored.resources().stream().map(Resource::id))
        .containsExactlyInAnyOrderElementsOf(ids);
    assertThat(
            db.sql("select count(*) from resource where hub_id=:hub")
                .param("hub", hub)
                .query(Integer.class)
                .single())
        .isEqualTo(8);
  }

  @Test
  void shouldRejectStaleUpdatesAndRollBackPartialCapacityChanges() {
    var stale = change(new Capacity(5, 1, 1, 1));
    settings.update(hub, change(new Capacity(2, 1, 0, 0)));
    assertThatThrownBy(() -> settings.update(hub, stale))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("another session");
    var hold = reserve("BARBER", start);
    bookings.confirm(hold.id());
    var before = settings.read(hub);
    assertThatThrownBy(() -> settings.update(hub, change(new Capacity(7, 0, 0, 0))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("upcoming bookings");
    assertThat(settings.read(hub)).isEqualTo(before);
    assertThat(bookings.findHold(hold.id()).orElseThrow().status()).isEqualTo("CONFIRMED");
    bookings.cancel(hold.id());
    assertThat(settings.update(hub, change(new Capacity(1, 0, 0, 0))).capacity().barberChairs())
        .isZero();
  }

  @Test
  void shouldAllowRetirementOfIdleBaysAndKeepTheOccupiedBay() {
    settings.update(hub, change(new Capacity(3, 0, 0, 0)));
    var hold = reserve(null, start);
    var resource =
        db.sql("select resource_id from resource_allocation where hold_id=:id")
            .param("id", hold.id())
            .query(UUID.class)
            .single();
    var saved = settings.update(hub, change(new Capacity(1, 0, 0, 0)));
    assertThat(saved.resources()).extracting(Resource::id).containsExactly(resource);
    assertThat(bookings.calendar(hub, start, start.plusDays(1))).hasSize(1);
  }

  @Test
  void shouldDriveCustomerAndOperatorSlotsFromWeeklyHours() {
    var before = settings.read(hub);
    var days =
        before.tradingHours().stream()
            .map(
                d ->
                    new TradingDay(
                        d.dayOfWeek(),
                        d.dayOfWeek() == 7,
                        LocalTime.of(9, 30),
                        LocalTime.of(12, 30)))
            .toList();
    settings.update(hub, new Update(0, before.capacity(), days));
    assertThat(customers.slots(UUID.randomUUID(), hub, LocalDate.parse("2035-01-07"), null, null))
        .isEmpty();
    var slots = customers.slots(UUID.randomUUID(), hub, start.toLocalDate(), null, null);
    assertThat(slots).hasSize(3);
    assertThat(slots.getFirst().startsAt().toLocalTime()).isEqualTo(LocalTime.of(9, 30));
    var operatorSlots = bookings.availability(hub, "EXPRESS_OIL", start, start.plusDays(1));
    assertThat(operatorSlots.getFirst().startsAt().toLocalTime()).isEqualTo(LocalTime.of(9, 30));
    assertThat(operatorSlots.getLast().endsAt().toLocalTime()).isEqualTo(LocalTime.of(12, 30));
    assertThat(bookings.availability(hub, "EXPRESS_OIL", start.withHour(12), start.plusDays(1)))
        .isEmpty();
    assertThatThrownBy(() -> reserve(null, start))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("trading hours");
    assertThat(reserve(null, slots.getLast().startsAt()).endsAt().toLocalTime())
        .isEqualTo(LocalTime.of(12, 30));
  }

  @Test
  void shouldRejectHoursThatWouldStrandAnExistingHoldThenAllowAfterCancellation() {
    var hold = reserve(null, start);
    var before = settings.read(hub);
    var closed =
        before.tradingHours().stream()
            .map(d -> new TradingDay(d.dayOfWeek(), true, d.opensAt(), d.closesAt()))
            .toList();
    var request = new Update(before.revision(), before.capacity(), closed);
    assertThatThrownBy(() -> settings.update(hub, request))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("upcoming booking");
    assertThat(settings.read(hub)).isEqualTo(before);
    bookings.cancel(hold.id());
    settings.update(hub, request);
    assertThat(customers.slots(UUID.randomUUID(), hub, start.toLocalDate(), null, null)).isEmpty();
    assertThat(bookings.availability(hub, "EXPRESS_OIL", start, start.plusDays(1))).isEmpty();
    assertThatThrownBy(() -> reserve(null, start)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void shouldEnforceSharedBookingLockBeforeChangingHours() throws Exception {
    var locked = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var tx = new TransactionTemplate(transactions);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var allocation =
          executor.submit(
              () ->
                  tx.execute(
                      status -> {
                        schedule.requireOpen(hub, start, start.plusHours(1));
                        locked.countDown();
                        try {
                          if (!release.await(5, TimeUnit.SECONDS))
                            throw new AssertionError("Test lock release timed out");
                        } catch (InterruptedException error) {
                          Thread.currentThread().interrupt();
                          throw new AssertionError(error);
                        }
                        return reserve(null, start);
                      }));
      assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
      var before = settings.read(hub);
      var days =
          before.tradingHours().stream()
              .map(d -> new TradingDay(d.dayOfWeek(), true, d.opensAt(), d.closesAt()))
              .toList();
      var attempt =
          executor.submit(() -> settings.update(hub, new Update(0, before.capacity(), days)));
      try {
        assertThatThrownBy(() -> attempt.get(150, TimeUnit.MILLISECONDS))
            .isInstanceOf(TimeoutException.class);
      } finally {
        release.countDown();
      }
      assertThat(allocation.get(5, TimeUnit.SECONDS).status()).isEqualTo("HELD");
      assertThatThrownBy(() -> attempt.get(5, TimeUnit.SECONDS))
          .hasCauseInstanceOf(IllegalStateException.class);
    }
  }

  @Test
  void shouldSerializeConcurrentEditorsWithOneWinner() throws Exception {
    var gate = new CountDownLatch(1);
    var original = settings.read(hub);
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      var futures = new ArrayList<Future<Boolean>>();
      for (int count : List.of(2, 3))
        futures.add(
            executor.submit(
                () -> {
                  gate.await();
                  try {
                    settings.update(
                        hub, new Update(0, new Capacity(count, 0, 0, 0), original.tradingHours()));
                    return true;
                  } catch (IllegalStateException conflict) {
                    return false;
                  }
                }));
      gate.countDown();
      int successes = 0;
      for (var future : futures) if (future.get(5, TimeUnit.SECONDS)) successes++;
      assertThat(successes).isEqualTo(1);
      assertThat(settings.read(hub).revision()).isEqualTo(1);
    }
  }

  @Test
  void shouldRejectUnknownHubAndInvalidWindows() {
    var unknown = UUID.randomUUID();
    assertThatThrownBy(() -> settings.read(unknown)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> settings.update(unknown, change(new Capacity(1, 0, 0, 0))))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> schedule.day(unknown, start.toLocalDate()))
        .isInstanceOf(IllegalArgumentException.class);
    var tx = new TransactionTemplate(transactions);
    assertThatThrownBy(
            () ->
                tx.execute(
                    s -> {
                      schedule.requireOpen(unknown, start, start.plusHours(1));
                      return true;
                    }))
        .isInstanceOf(IllegalArgumentException.class);
    for (var end : List.of(start, start.minusHours(1), start.plusDays(1)))
      assertThatThrownBy(
              () ->
                  tx.execute(
                      s -> {
                        schedule.requireOpen(hub, start, end);
                        return true;
                      }))
          .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void shouldSupportAnEmptyNewHubBeforeCapacityIsConfigured() {
    db.sql("update resource set active=false where hub_id=:hub").param("hub", hub).update();
    assertThat(settings.read(hub).capacity().expressBays()).isZero();
    assertThat(settings.update(hub, change(new Capacity(1, 0, 0, 0))).resources()).hasSize(1);
  }
}
