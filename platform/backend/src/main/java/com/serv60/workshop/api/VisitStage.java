package com.serv60.workshop.api;

import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * The stages of an express visit on the floor, in order. The 60-minute target runs from
 * {@code CHECKED_IN} until {@code READY}; handover is recorded when the customer collects the car.
 */
public enum VisitStage {
  CHECKED_IN(0.3),
  INSPECTING(0.7),
  SERVICING(2.0),
  QUALITY_CHECK(0.5),
  WASHING(1.0),
  READY(0),
  HANDED_OVER(0);

  private final double simulatedShare;

  VisitStage(double simulatedShare) {
    this.simulatedShare = simulatedShare;
  }

  /** How long the demo simulation keeps a visit in this stage, as a multiple of its base unit. */
  public double simulatedShare() {
    return simulatedShare;
  }

  /** The only stage that may be recorded after this one. */
  public Optional<VisitStage> next() {
    return ordinal() + 1 < values().length ? Optional.of(values()[ordinal() + 1]) : Optional.empty();
  }

  /** Whether time in this stage still counts toward the 60-minute target. */
  public boolean countsTowardTarget() {
    return compareTo(READY) < 0;
  }

  /** The stage that may be recorded after {@code current}, or {@code CHECKED_IN} for a new visit. */
  public static Optional<VisitStage> after(VisitStage current) {
    return current == null ? Optional.of(CHECKED_IN) : current.next();
  }

  /** SQL array literal of the stages in order, for ordering rows by stage. */
  public static String sqlOrder() {
    return Arrays.stream(values())
        .map(stage -> "'" + stage.name() + "'")
        .collect(Collectors.joining(",", "array[", "]"));
  }
}
