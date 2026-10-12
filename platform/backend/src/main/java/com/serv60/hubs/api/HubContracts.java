package com.serv60.hubs.api;

import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

public final class HubContracts {
  private HubContracts() {}

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
