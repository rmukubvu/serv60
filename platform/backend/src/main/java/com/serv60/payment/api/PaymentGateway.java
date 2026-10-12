package com.serv60.payment.api;

/** Provider port for creating hosted payment checkouts. */
public interface PaymentGateway {
  String provider();

  PaymentContracts.Checkout createCheckout(PaymentContracts.PaymentOrder order, String idempotencyKey);
}
