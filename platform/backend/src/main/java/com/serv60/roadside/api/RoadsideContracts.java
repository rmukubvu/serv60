package com.serv60.roadside.api;

import java.time.OffsetDateTime;
import java.util.UUID;

public final class RoadsideContracts {
  private RoadsideContracts() {}

  public record CreateIncident(UUID customerId, UUID vehicleId, String location,
                               String description) {}

  public record Incident(UUID id, UUID customerId, UUID vehicleId, String status,
                         String location, String description, OffsetDateTime createdAt) {}

  public record RecoveryOption(String provider, String quoteReference, long amountMinor,
                               String currency, int estimatedMinutes) {}
}
