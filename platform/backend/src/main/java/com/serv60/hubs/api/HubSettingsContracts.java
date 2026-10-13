package com.serv60.hubs.api;

import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/** Hub configuration contracts; capacities count simultaneous customers, not daily bookings. */
public final class HubSettingsContracts {
  private HubSettingsContracts() {}

  public record TradingDay(int dayOfWeek, boolean closed, LocalTime opensAt, LocalTime closesAt) {
    public TradingDay {
      if (dayOfWeek < 1
          || dayOfWeek > 7
          || opensAt == null
          || closesAt == null
          || opensAt.getSecond() != 0
          || closesAt.getSecond() != 0
          || opensAt.getNano() != 0
          || closesAt.getNano() != 0
          || (!closed && java.time.Duration.between(opensAt, closesAt).toMinutes() < 60))
        throw new IllegalArgumentException(
            "Each open day needs at least one hour, with opening before closing");
    }
  }

  public record Capacity(int expressBays, int barberChairs, int massageRooms, int workspaces) {
    public Capacity {
      if (expressBays < 0
          || expressBays > 100
          || barberChairs < 0
          || barberChairs > 50
          || massageRooms < 0
          || massageRooms > 50
          || workspaces < 0
          || workspaces > 50)
        throw new IllegalArgumentException(
            "Set 1–100 service bays and 0–50 of each complimentary facility");
    }
  }

  public record Update(long revision, Capacity capacity, List<TradingDay> tradingHours) {
    public Update {
      if (revision < 0
          || capacity == null
          || capacity.expressBays() < 1
          || tradingHours == null
          || tradingHours.size() != 7
          || tradingHours.stream().anyMatch(java.util.Objects::isNull)
          || tradingHours.stream().map(TradingDay::dayOfWeek).distinct().count() != 7)
        throw new IllegalArgumentException(
            "Provide a revision, capacity and all seven unique trading days");
      tradingHours =
          tradingHours.stream()
              .sorted(java.util.Comparator.comparingInt(TradingDay::dayOfWeek))
              .toList();
    }
  }

  public record Resource(UUID id, String code, String kind) {}

  public record Settings(
      UUID hubId,
      String name,
      String timezone,
      boolean published,
      long revision,
      Capacity capacity,
      List<TradingDay> tradingHours,
      List<Resource> resources) {}
}
