package com.serv60.payment.api;

/** The payment provider failed or timed out; the call is safe to retry with the same key. */
public final class PaymentProviderUnavailable extends RuntimeException {
  public PaymentProviderUnavailable(String message, Throwable cause) {
    super(message, cause);
  }
}
