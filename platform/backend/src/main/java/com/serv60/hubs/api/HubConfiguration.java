package com.serv60.hubs.api;

import java.util.UUID;

/** Read access to a hub's capacity and weekly trading hours. */
public interface HubConfiguration {
  /** Returns a consistent settings snapshot, or throws IllegalArgumentException for an unknown hub. */
  HubSettingsContracts.Settings read(UUID hub);
}
