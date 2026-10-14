package com.serv60.roadside.api;

import java.util.Arrays;
import java.util.stream.Collectors;

/** Roadside incident lifecycle, stored by name in {@code roadside_incident.status}. */
public enum IncidentStatus {
  OPEN,
  QUOTED,
  AWAITING_PAYMENT,
  SEARCHING,
  ASSIGNED,
  EN_ROUTE,
  ON_SCENE,
  TOWING,
  CANCELLED;

  /** Still waiting for a truck: the customer may cancel and dispatch may still act. */
  public boolean awaitingDispatch() {
    return switch (this) {
      case OPEN, QUOTED, AWAITING_PAYMENT, SEARCHING -> true;
      case ASSIGNED, EN_ROUTE, ON_SCENE, TOWING, CANCELLED -> false;
    };
  }

  /** On the live dispatch board: anything not yet closed. */
  public boolean active() {
    return this != CANCELLED;
  }

  /** The customer may still pick a quote. */
  public boolean acceptsQuote() {
    return this == QUOTED || this == AWAITING_PAYMENT;
  }

  /** SQL list literal of statuses awaiting dispatch, for example {@code ('OPEN','QUOTED')}. */
  public static String awaitingDispatchSql() {
    return sqlList(true);
  }

  /** SQL list literal of statuses shown on the dispatch board. */
  public static String activeSql() {
    return sqlList(false);
  }

  private static String sqlList(boolean awaitingOnly) {
    return Arrays.stream(values())
        .filter(status -> awaitingOnly ? status.awaitingDispatch() : status.active())
        .map(status -> "'" + status.name() + "'")
        .collect(Collectors.joining(",", "(", ")"));
  }
}
