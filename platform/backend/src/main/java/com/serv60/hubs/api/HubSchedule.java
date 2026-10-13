package com.serv60.hubs.api;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Trading-hour boundary shared by customer and operator booking paths. */
public interface HubSchedule {
  /** Returns the effective weekly hours for a local hub date. */
  HubSettingsContracts.TradingDay day(UUID hub, LocalDate date);

  /**
   * Locks configuration for the caller's transaction and rejects allocations outside trading hours.
   */
  void requireOpen(UUID hub, OffsetDateTime start, OffsetDateTime end);
}
