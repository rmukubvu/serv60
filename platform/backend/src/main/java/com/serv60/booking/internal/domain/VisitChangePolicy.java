package com.serv60.booking.internal.domain;

import java.time.Duration;
import java.time.Instant;

/** Changes need 48 hours' notice on both the original and replacement appointment. */
public final class VisitChangePolicy {
  private VisitChangePolicy() {}

  public static boolean canChange(String status, Instant startsAt, Instant now) {
    return "CONFIRMED".equals(status) && !startsAt.minus(Duration.ofHours(48)).isBefore(now);
  }

  public static void requireChange(
      String status, Instant startsAt, Instant replacement, Instant now) {
    if (!canChange(status, startsAt, now)) {
      throw new IllegalStateException(
          "Bookings can only be changed at least 48 hours before arrival");
    }
    if (replacement.isBefore(now.plus(Duration.ofHours(48)))) {
      throw new IllegalArgumentException("Choose a replacement at least 48 hours from now");
    }
  }

  /** Held reservations may cancel any time; confirmed visits need 48 hours' notice. */
  public static boolean canCancel(String status, Instant startsAt, Instant now) {
    if ("HELD".equals(status)) return true;
    return canChange(status, startsAt, now);
  }

  public static void requireCancel(String status, Instant startsAt, Instant now) {
    if (!canCancel(status, startsAt, now)) {
      throw new IllegalStateException(
          "Confirmed bookings can only be cancelled at least 48 hours before arrival");
    }
  }
}
