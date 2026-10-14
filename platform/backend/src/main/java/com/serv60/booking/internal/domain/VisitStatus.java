package com.serv60.booking.internal.domain;

/** Lifecycle of a visit hold, stored by name in {@code visit_hold.status}. */
public enum VisitStatus {
  HELD,
  CONFIRMED,
  CANCELLED,
  EXPIRED;

  /** Whether the visit has left the active lifecycle and can never change again. */
  public boolean closed() {
    return switch (this) {
      case HELD, CONFIRMED -> false;
      case CANCELLED, EXPIRED -> true;
    };
  }
}
