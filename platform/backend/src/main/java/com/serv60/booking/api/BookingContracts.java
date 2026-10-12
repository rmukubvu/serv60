package com.serv60.booking.api;

import java.time.OffsetDateTime;
import java.util.UUID;

public final class BookingContracts {
  private BookingContracts() {}

  public record Availability(UUID hubId, String serviceCode, OffsetDateTime startsAt,
                             OffsetDateTime endsAt, String readinessPolicy) {}

  public record CreateHold(UUID hubId, UUID customerId, UUID vehicleId, String serviceCode,
                           OffsetDateTime startsAt, OffsetDateTime endsAt, String policy,
                           String complimentaryServiceCode) {
    public CreateHold(UUID hubId, UUID customerId, UUID vehicleId, String serviceCode,
                      OffsetDateTime startsAt, OffsetDateTime endsAt, String policy) {
      this(hubId, customerId, vehicleId, serviceCode, startsAt, endsAt, policy, null);
    }
  }

  public record Hold(UUID id, UUID hubId, UUID customerId, String serviceCode,
                     OffsetDateTime startsAt, OffsetDateTime endsAt, String status,
                     String policy, OffsetDateTime expiresAt, String complimentaryServiceCode) {
    public Hold(UUID id, UUID hubId, UUID customerId, String serviceCode,
                OffsetDateTime startsAt, OffsetDateTime endsAt, String status,
                String policy, OffsetDateTime expiresAt) {
      this(id, hubId, customerId, serviceCode, startsAt, endsAt, status, policy, expiresAt, null);
    }
  }

  public record PartsReadiness(String sku, int usableQuantity, int reservedQuantity,
                               int pendingDemand) {}

  public record CalendarEntry(UUID allocationId, UUID holdId, UUID resourceId,
                              String resourceCode, String serviceCode,
                              OffsetDateTime startsAt, OffsetDateTime endsAt,
                              String status) {}
}
