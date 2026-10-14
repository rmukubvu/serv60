package com.serv60.ops.api;

import java.util.List;
import java.util.UUID;

/** Deterministic operator insights — ranking and alerts, not free-text chat. */
public final class OpsInsightsContracts {
  private OpsInsightsContracts() {}

  public record FloorAlert(String severity, String code, String title, String detail) {
    public FloorAlert {
      if (severity == null || severity.isBlank()) {
        throw new IllegalArgumentException("severity is required");
      }
      if (code == null || code.isBlank()) {
        throw new IllegalArgumentException("code is required");
      }
      if (title == null || title.isBlank()) {
        throw new IllegalArgumentException("title is required");
      }
      if (detail == null) {
        throw new IllegalArgumentException("detail is required");
      }
    }
  }

  public record DispatchPriority(
      UUID incidentId,
      int priorityScore,
      String reason,
      String suggestedProvider,
      Integer suggestedEtaMinutes) {
    public DispatchPriority {
      if (incidentId == null) {
        throw new IllegalArgumentException("incidentId is required");
      }
      if (reason == null || reason.isBlank()) {
        throw new IllegalArgumentException("reason is required");
      }
    }
  }

  public record OpsInsights(List<FloorAlert> alerts, List<DispatchPriority> dispatchPriority) {
    public OpsInsights {
      alerts = List.copyOf(alerts == null ? List.of() : alerts);
      dispatchPriority = List.copyOf(dispatchPriority == null ? List.of() : dispatchPriority);
    }
  }
}
