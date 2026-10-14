package com.serv60.payment.api;

import java.util.Arrays;
import java.util.Optional;

/**
 * Payment order lifecycle, stored by name in {@code payment_order.status}. Provider events may
 * arrive out of order, so an order only ever moves to a status of higher rank: a late success can
 * never overwrite a refund.
 */
public enum PaymentStatus {
  PENDING(0),
  FAILED(1),
  CANCELLED(1),
  PAID(2),
  PARTIALLY_REFUNDED(3),
  REFUNDED(4);

  private final int rank;

  PaymentStatus(int rank) {
    this.rank = rank;
  }

  /** Whether an order in this status may move to {@code next}. */
  public boolean canAdvanceTo(PaymentStatus next) {
    return next.rank > rank;
  }

  /** Whether money has gone back to the customer, fully or in part. */
  public boolean refunded() {
    return switch (this) {
      case PARTIALLY_REFUNDED, REFUNDED -> true;
      case PENDING, FAILED, CANCELLED, PAID -> false;
    };
  }

  /** Parses a stored or provider status; empty for anything this platform does not model. */
  public static Optional<PaymentStatus> of(String status) {
    return Arrays.stream(values()).filter(value -> value.name().equals(status)).findFirst();
  }
}
