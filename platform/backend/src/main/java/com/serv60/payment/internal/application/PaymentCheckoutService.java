package com.serv60.payment.internal.application;

import com.serv60.payment.api.PaymentContracts;
import com.serv60.payment.api.PaymentGateway;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Coordinates provider-neutral checkout creation and keeps provider adapters outside the domain. */
@Service
public class PaymentCheckoutService {
  private final PaymentService payments;
  private final List<PaymentGateway> gateways;

  public PaymentCheckoutService(PaymentService payments, List<PaymentGateway> gateways) {
    this.payments = payments;
    this.gateways = List.copyOf(gateways);
  }

  public PaymentContracts.PaymentOrder start(UUID paymentId, String idempotencyKey) {
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      throw new IllegalArgumentException("Idempotency-Key is required");
    }
    var order = payments.find(paymentId)
        .orElseThrow(() -> new IllegalArgumentException("Payment order not found"));
    if (!order.status().equals("PENDING")) throw new IllegalStateException("This payment no longer needs checkout");
    payments.requirePayable(order);
    if (order.checkoutUrl() != null && !order.checkoutUrl().isBlank()) return order;
    var gateway = gateways.stream()
        .filter(candidate -> candidate.provider().equalsIgnoreCase(order.provider()))
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("Payment provider is not configured: " + order.provider()));
    return payments.attachCheckout(paymentId, gateway.createCheckout(order, "serv60-payment-" + order.id()));
  }
}
