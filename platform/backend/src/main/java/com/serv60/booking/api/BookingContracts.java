package com.serv60.booking.api;

import java.time.OffsetDateTime;
import java.util.UUID;

public final class BookingContracts {
  private BookingContracts() {}

  public record Availability(UUID hubId, String serviceCode, OffsetDateTime startsAt,
                             OffsetDateTime endsAt, String readinessPolicy) {}

  public record CreateHold(UUID hubId, UUID customerId, UUID vehicleId, String serviceCode,
                           OffsetDateTime startsAt, OffsetDateTime endsAt, String policy) {}

  public record Hold(UUID id, UUID hubId, UUID customerId, String serviceCode,
                     OffsetDateTime startsAt, OffsetDateTime endsAt, String status,
                     String policy, OffsetDateTime expiresAt) {}

  public record PartsReadiness(String sku, int usableQuantity, int reservedQuantity,
                               int pendingDemand) {}

  public record CalendarEntry(UUID allocationId, UUID holdId, UUID resourceId,
                              String resourceCode, String serviceCode,
                              OffsetDateTime startsAt, OffsetDateTime endsAt,
                              String status) {}
}
