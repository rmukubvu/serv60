package com.serv60.hubs.api;

import java.util.*;

/** Published hub catalogue for customer discovery. */
public interface HubDiscovery {
  List<HubContracts.Hub> nearby(double latitude, double longitude, String query);

  HubContracts.Hub require(UUID id);
}
