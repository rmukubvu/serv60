package com.serv60.serviceplanning.api;

import java.util.List;
import java.util.UUID;

public final class ServicePlanningContracts {
  private ServicePlanningContracts() {}
  public record ServicePlan(UUID id, UUID hubId, String code, String name,
                            int durationMinutes, List<RequiredPart> requiredParts) {}
  public record RequiredPart(String sku, int quantity) {}
}
