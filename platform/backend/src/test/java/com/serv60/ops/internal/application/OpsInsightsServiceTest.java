package com.serv60.ops.internal.application;

import static org.assertj.core.api.Assertions.assertThat;

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
