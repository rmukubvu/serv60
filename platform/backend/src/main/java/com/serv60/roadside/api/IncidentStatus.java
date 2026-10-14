package com.serv60.roadside.api;

import java.util.Arrays;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Roadside incident lifecycle, stored by name in {@code roadside_incident.status}.
 *
 * <pre>
 * OPEN/QUOTED -> SEARCHING (customer accepted a quote; dispatcher must confirm the partner)
 * SEARCHING or ASSIGNED -> EN_ROUTE (truck confirmed) -> ON_SCENE -> TOWING -> COMPLETED
 * any open state -> CANCELLED; QUOTED with every quote expired -> EXPIRED
 * </pre>
 */
public enum IncidentStatus {
  OPEN,
  QUOTED,
  SEARCHING,
  ASSIGNED,
  EN_ROUTE,
  ON_SCENE,
  TOWING,
  COMPLETED,
  CANCELLED,
  EXPIRED;

  /** Closed for good: off every board and never changes again. */
  public boolean terminal() {
    return switch (this) {
      case COMPLETED, CANCELLED, EXPIRED -> true;
      case OPEN, QUOTED, SEARCHING, ASSIGNED, EN_ROUTE, ON_SCENE, TOWING -> false;
    };
  }

  /** On the live dispatch board. */
  public boolean active() {
    return !terminal();
  }

  /** No truck is confirmed yet, so dispatch must act and the customer may still cancel. */
  public boolean awaitingDispatch() {
    return switch (this) {
      case OPEN, QUOTED, SEARCHING -> true;
      case ASSIGNED, EN_ROUTE, ON_SCENE, TOWING, COMPLETED, CANCELLED, EXPIRED -> false;
    };
  }

  /** The customer may still pick a quote. */
  public boolean acceptsQuote() {
    return this == QUOTED;
  }

  /** The steps a dispatcher may record. Anything else is refused. */
  public boolean canProgressTo(IncidentStatus next) {
    return switch (this) {
      case SEARCHING -> next == EN_ROUTE;
      case ASSIGNED -> next == EN_ROUTE || next == ON_SCENE;
      case EN_ROUTE -> next == ON_SCENE;
      case ON_SCENE -> next == TOWING || next == COMPLETED;
      case TOWING -> next == COMPLETED;
      case OPEN, QUOTED, COMPLETED, CANCELLED, EXPIRED -> false;
    };
  }

  /** SQL list literal of statuses awaiting dispatch, for example {@code ('OPEN','QUOTED')}. */
  public static String awaitingDispatchSql() {
    return sqlList(IncidentStatus::awaitingDispatch);
  }

  /** SQL list literal of statuses shown on the dispatch board. */
  public static String activeSql() {
    return sqlList(IncidentStatus::active);
  }

  /** SQL list literal of statuses in which a partner's truck is committed to a job. */
  public static String committedSql() {
    return sqlList(status -> status == SEARCHING || status == ASSIGNED || status == EN_ROUTE
        || status == ON_SCENE || status == TOWING);
  }

  private static String sqlList(Predicate<IncidentStatus> include) {
    return Arrays.stream(values()).filter(include)
        .map(status -> "'" + status.name() + "'")
        .collect(Collectors.joining(",", "(", ")"));
  }
}
