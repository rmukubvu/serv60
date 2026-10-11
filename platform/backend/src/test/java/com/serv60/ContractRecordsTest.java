package com.serv60;

import static org.assertj.core.api.Assertions.assertThat;

import com.serv60.booking.api.BookingContracts;
import com.serv60.serviceplanning.api.ServicePlanningContracts;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ContractRecordsTest {
  private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final OffsetDateTime TIME = OffsetDateTime.of(2026, 10, 11, 8, 0, 0, 0, ZoneOffset.UTC);

  @Test
  void bookingContractsExposeTheirPublishedFields() {
    var availability = new BookingContracts.Availability(ID, "BARBER", TIME, TIME.plusHours(1), "READY_STOCK");
    var request = new BookingContracts.CreateHold(ID, ID, null, "BARBER", TIME, TIME.plusHours(1), "READY_STOCK");
    var hold = new BookingContracts.Hold(ID, ID, ID, "BARBER", TIME, TIME.plusHours(1), "HELD", "READY_STOCK", TIME);
    var readiness = new BookingContracts.PartsReadiness("OIL-5W30", 10, 2, 3);
    var calendar = new BookingContracts.CalendarEntry(ID, ID, ID, "EXPRESS-01", "EXPRESS_OIL",
        TIME, TIME.plusHours(1), "HELD");

    assertThat(availability.serviceCode()).isEqualTo("BARBER");
    assertThat(request.customerId()).isEqualTo(ID);
    assertThat(hold.status()).isEqualTo("HELD");
    assertThat(readiness.pendingDemand()).isEqualTo(3);
    assertThat(calendar.resourceCode()).isEqualTo("EXPRESS-01");
  }

  @Test
  void servicePlanningContractsExposePartsAndPlans() {
    var part = new ServicePlanningContracts.RequiredPart("OIL-5W30", 1);
    var plan = new ServicePlanningContracts.ServicePlan(ID, ID, "EXPRESS_OIL", "Express oil",
        60, List.of(part));

    assertThat(part.sku()).isEqualTo("OIL-5W30");
    assertThat(plan.requiredParts()).containsExactly(part);
    assertThat(plan.durationMinutes()).isEqualTo(60);
  }
}
