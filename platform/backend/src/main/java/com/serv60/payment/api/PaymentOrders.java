package com.serv60.payment.api;

/** Creates an idempotent payment order without calling the payment provider. */
public interface PaymentOrders {
  PaymentContracts.PaymentOrder create(PaymentContracts.CreatePayment request, String key);
}
