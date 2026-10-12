package com.serv60.payment.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.serv60.payment.api.PaymentContracts;
import com.serv60.payment.api.PaymentGateway;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PaymentCheckoutServiceTest {
  private static final UUID PAYMENT = UUID.fromString("00000000-0000-0000-0000-000000000098");
  private static final UUID CUSTOMER = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID HOLD = UUID.fromString("00000000-0000-0000-0000-000000000099");

  @Mock PaymentService payments;
  @Mock PaymentGateway gateway;
  private PaymentCheckoutService service;

  @BeforeEach
  void setUp() { service = new PaymentCheckoutService(payments, List.of(gateway)); }

  @Test
  void createsAndAttachesProviderCheckout() {
    var order = order(null, null);
    var checkout = new PaymentContracts.Checkout("ch_123", "https://checkout.test/ch_123");
    var attached = order("ch_123", checkout.redirectUrl());
    when(payments.find(PAYMENT)).thenReturn(Optional.of(order));
    when(gateway.provider()).thenReturn("YOCO");
    when(gateway.createCheckout(order, "serv60-payment-" + PAYMENT)).thenReturn(checkout);
    when(payments.attachCheckout(PAYMENT, checkout)).thenReturn(attached);

    assertThat(service.start(PAYMENT, "checkout-key")).isSameAs(attached);
    verify(payments).attachCheckout(PAYMENT, checkout);
  }

  @Test
  void returnsExistingCheckoutWithoutCallingProvider() {
    var order = order("ch_123", "https://checkout.test/ch_123");
    when(payments.find(PAYMENT)).thenReturn(Optional.of(order));

    assertThat(service.start(PAYMENT, "replay-key")).isSameAs(order);
  }

  @Test
  void rejectsMissingPaymentAndMissingKey() {
    when(payments.find(PAYMENT)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.start(PAYMENT, "key"))
        .isInstanceOf(IllegalArgumentException.class).hasMessage("Payment order not found");
    assertThatThrownBy(() -> service.start(PAYMENT, " "))
        .isInstanceOf(IllegalArgumentException.class).hasMessage("Idempotency-Key is required");
  }

  @Test
  void rejectsUnconfiguredProvider() {
    var order = order(null, null);
    when(payments.find(PAYMENT)).thenReturn(Optional.of(order));
    when(gateway.provider()).thenReturn("PEACH");
    assertThatThrownBy(() -> service.start(PAYMENT, "key"))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("not configured");
  }

  private PaymentContracts.PaymentOrder order(String reference, String checkoutUrl) {
    return new PaymentContracts.PaymentOrder(PAYMENT, CUSTOMER, HOLD, 24900, "ZAR", "YOCO",
        "PENDING", reference, checkoutUrl, OffsetDateTime.parse("2026-10-12T08:00:00Z"));
  }
}
