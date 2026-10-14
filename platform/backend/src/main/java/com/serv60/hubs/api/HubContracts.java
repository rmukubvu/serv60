package com.serv60.hubs.api;

import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

public final class HubContracts {
  private HubContracts() {}

  /** A published hub. {@code opensAt} and {@code closesAt} are today's hours; both null when closed today. */
  public record Hub(
      UUID id,
      String name,
      String address,
      String timezone,
      double latitude,
      double longitude,
      double distanceKm,
      LocalTime opensAt,
      LocalTime closesAt,
      List<String> complimentaryServices) {}
}
