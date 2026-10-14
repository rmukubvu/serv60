package com.serv60.ops.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.serv60.booking.api.BookingContracts.CalendarEntry;
import com.serv60.booking.api.BookingContracts.PartsReadiness;
import com.serv60.hubs.api.HubSettingsContracts.*;
import java.time.*;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OpsInsightsServiceTest {
  @Test
  void shouldFlagPartsShortageWhenDemandExceedsAvailable() {
    var alerts =
        OpsInsightsService.partsAlerts(
            List.of(
                new PartsReadiness("FILTER-KIT", 4, 2, 3),
                new PartsReadiness("OIL-5W40", 10, 1, 2)));
    assertThat(alerts).hasSize(1);
    assertThat(alerts.getFirst().code()).isEqualTo("PARTS_SHORTAGE");
    assertThat(alerts.getFirst().severity()).isEqualTo("CRITICAL");
  }

  @Test
  void shouldRaiseACriticalAlertForMoneyThatNeedsAPerson() {
    var hold = UUID.randomUUID();
    var alerts = OpsInsightsService.paymentAlerts(List.of(
        new com.serv60.booking.api.BookingContracts.PaymentAttention(hold, UUID.randomUUID(),
            "PAID_VISIT_NEEDS_RECONCILIATION", "hold_expired", 24900L, OffsetDateTime.now()),
        new com.serv60.booking.api.BookingContracts.PaymentAttention(hold, UUID.randomUUID(),
            "REFUND_REQUIRED", null, null, OffsetDateTime.now())));

    assertThat(alerts).extracting(a -> a.severity() + ":" + a.code())
        .containsExactly("CRITICAL:PAID_VISIT_NEEDS_RECONCILIATION", "CRITICAL:REFUND_REQUIRED");
    assertThat(alerts.getFirst().detail()).contains("R249.00", "hold expired");
  }

  @Test
  void shouldWarnWhenPartsBufferIsThin() {
    var alerts =
        OpsInsightsService.partsAlerts(List.of(new PartsReadiness("FILTER-KIT", 5, 1, 2)));
    assertThat(alerts).singleElement().extracting(a -> a.code()).isEqualTo("PARTS_TIGHT");
  }

  @Test
  void shouldDetectFloorPressureAboveExpressBayCount() {
    UUID hub = UUID.randomUUID();
    ZoneId zone = ZoneId.of("Africa/Johannesburg");
    ZonedDateTime now = ZonedDateTime.of(2035, 3, 10, 8, 0, 0, 0, zone);
    OffsetDateTime slot = now.withHour(14).toOffsetDateTime();
    var calendar =
        List.of(
            entry(hub, "EXPRESS_OIL", slot, "BAY-1"),
            entry(hub, "EXPRESS_OIL", slot, "BAY-2"),
            entry(hub, "EXPRESS_OIL", slot, "BAY-3"),
            entry(hub, "BARBER", slot, "CHAIR-1"));
    var settings =
        new Settings(
            hub,
            "Sea Point",
            zone.getId(),
            true,
            1L,
            new Capacity(2, 1, 1, 1),
            List.of(),
            List.of());
    var alerts = OpsInsightsService.floorPressureAlerts(calendar, settings, now);
    assertThat(alerts).singleElement().extracting(a -> a.code()).isEqualTo("FLOOR_PRESSURE");
  }

  @Test
  void shouldSurfaceHeldVisitsStartingSoon() {
    ZoneId zone = ZoneId.of("Africa/Johannesburg");
    ZonedDateTime now = ZonedDateTime.of(2035, 3, 10, 10, 0, 0, 0, zone);
    var calendar =
        List.of(
            new CalendarEntry(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "BAY-1",
                "EXPRESS_OIL",
                now.plusMinutes(45).toOffsetDateTime(),
                now.plusMinutes(105).toOffsetDateTime(),
                "HELD"));
    var alerts = OpsInsightsService.lateRiskAlerts(calendar, now);
    assertThat(alerts).singleElement().extracting(a -> a.code()).isEqualTo("HELD_SOON");
  }

  @Test
  void shouldRankWaitingIncidentsAndSuggestTheNearestTruckForThoseWithoutOne() {
    var board = org.mockito.Mockito.mock(com.serv60.booking.api.HubBookingBoard.class);
    var hubs = org.mockito.Mockito.mock(com.serv60.hubs.api.HubConfiguration.class);
    var roadside = org.mockito.Mockito.mock(com.serv60.roadside.api.RoadsideDispatch.class);
    var hub = UUID.randomUUID();
    var now = Instant.parse("2035-03-10T08:00:00Z");
    var service = new OpsInsightsService(board, hubs, roadside, Clock.fixed(now, ZoneOffset.UTC));
    org.mockito.Mockito.when(hubs.read(hub)).thenReturn(new Settings(hub, "Sea Point",
        "Africa/Johannesburg", true, 1L, new Capacity(2, 1, 1, 1), List.of(), List.of()));
    org.mockito.Mockito.when(board.calendar(org.mockito.ArgumentMatchers.eq(hub),
        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(List.of());
    org.mockito.Mockito.when(board.partsReadiness(hub, 50))
        .thenReturn(List.of(new PartsReadiness("FILTER-KIT", 1, 0, 3)));
    var stranded = incident("SEARCHING", now.minusSeconds(30 * 60));
    var covered = incident("EN_ROUTE", now.minusSeconds(5 * 60));
    var quoted = incident("QUOTED", now);
    org.mockito.Mockito.when(roadside.dispatchBoard(hub, 50)).thenReturn(List.of(
        new com.serv60.roadside.api.RoadsideContracts.DispatchBoardItem(covered, -33.9, 18.4, "City Tow", null, null, 12, null, false, hub, "Near Tow", 8, 1.4),
        new com.serv60.roadside.api.RoadsideContracts.DispatchBoardItem(stranded, -33.9, 18.4, null, null, null, null, "REQUESTED", true, hub, "Near Tow", 8, 1.4),
        new com.serv60.roadside.api.RoadsideContracts.DispatchBoardItem(quoted, null, null, null, null, null, null, null, false, null, null, null, null)));

    var insights = service.forHub(hub);

    assertThat(insights.alerts()).extracting(a -> a.code()).containsExactly("PARTS_SHORTAGE");
    assertThat(insights.dispatchPriority()).extracting(p -> p.incidentId())
        .containsExactly(stranded.id(), quoted.id(), covered.id());
    assertThat(insights.dispatchPriority().getFirst().suggestedProvider()).isEqualTo("Near Tow");
    assertThat(insights.dispatchPriority().getFirst().reason()).contains("ride home requested", "nearest Near Tow");
    assertThat(insights.dispatchPriority().getLast().reason()).contains("truck City Tow", "ETA 12m");
  }

  @Test
  void shouldRejectIncompleteAlertsAndPrioritiesAndCopyLists() {
    assertThatThrownBy(() -> new com.serv60.ops.api.OpsInsightsContracts.FloorAlert(" ", "C", "T", ""))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new com.serv60.ops.api.OpsInsightsContracts.FloorAlert("INFO", null, "T", ""))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new com.serv60.ops.api.OpsInsightsContracts.FloorAlert("INFO", "C", "", ""))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new com.serv60.ops.api.OpsInsightsContracts.FloorAlert("INFO", "C", "T", null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new com.serv60.ops.api.OpsInsightsContracts.DispatchPriority(null, 1, "r", null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new com.serv60.ops.api.OpsInsightsContracts.DispatchPriority(UUID.randomUUID(), 1, " ", null, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(new com.serv60.ops.api.OpsInsightsContracts.OpsInsights(null, null).alerts()).isEmpty();
  }

  private static com.serv60.roadside.api.RoadsideContracts.Incident incident(String status, Instant created) {
    return new com.serv60.roadside.api.RoadsideContracts.Incident(UUID.randomUUID(), UUID.randomUUID(), null,
        status, "Cape Town", "Flat tyre", created.atOffset(ZoneOffset.UTC));
  }

  private static CalendarEntry entry(
      UUID hubIgnored, String service, OffsetDateTime starts, String resource) {
    return new CalendarEntry(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        resource,
        service,
        starts,
        starts.plusHours(1),
        "CONFIRMED");
  }
}
