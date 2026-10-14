package com.serv60.booking.internal.domain;

import java.time.Duration;
import java.time.Instant;

/** Changes need 48 hours' notice on both the original and replacement appointment. */
public final class VisitChangePolicy {
  private static final Duration NOTICE = Duration.ofHours(48);

  private VisitChangePolicy() {}

  /** A confirmed visit may change until and including 48 hours before it starts. */
  public static boolean canChange(VisitStatus status, Instant startsAt, Instant now) {
    return switch (status) {
      case CONFIRMED -> !startsAt.minus(NOTICE).isBefore(now);
      case HELD, CANCELLED, EXPIRED -> false;
    };
  }

  public static void requireChange(
      VisitStatus status, Instant startsAt, Instant replacement, Instant now) {
    if (!canChange(status, startsAt, now)) {
      throw new IllegalStateException(
          "Bookings can only be changed at least 48 hours before arrival");
    }
    if (replacement.isBefore(now.plus(NOTICE))) {
      throw new IllegalArgumentException("Choose a replacement at least 48 hours from now");
    }
  }

  /** Held reservations may cancel any time; confirmed visits need 48 hours' notice. */
  public static boolean canCancel(VisitStatus status, Instant startsAt, Instant now) {
    return switch (status) {
      case HELD -> true;
      case CONFIRMED -> canChange(status, startsAt, now);
      case CANCELLED, EXPIRED -> false;
    };
  }

  public static void requireCancel(VisitStatus status, Instant startsAt, Instant now) {
    if (!canCancel(status, startsAt, now)) {
      throw new IllegalStateException(
          "Confirmed bookings can only be cancelled at least 48 hours before arrival");
    }
  }
}
