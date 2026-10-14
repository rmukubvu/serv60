package com.serv60.payment.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import java.time.OffsetDateTime;
import java.util.UUID;

public final class PaymentContracts {
  private PaymentContracts() {}

  public record CreatePayment(@NotNull UUID customerId, @NotNull UUID holdId,
                              @Positive long amountMinor,
                              @Pattern(regexp = "[A-Za-z]{3}") String currency,
                              @Pattern(regexp = "[A-Za-z]+") String provider) {}

  public record PaymentOrder(UUID id, UUID customerId, UUID holdId, long amountMinor,
                             String currency, String provider, String status,
                             String providerReference, String checkoutUrl,
                             OffsetDateTime createdAt) {}

  public record Checkout(String providerReference, String redirectUrl) {}

  public record ProviderPaymentEvent(String provider, String eventId,
                                     UUID paymentOrderId, String providerReference,
                                     long amountMinor, String currency, String status) {}

  /**
   * Published synchronously inside the reconciliation transaction when an order first becomes
   * PAID, so the booking that owns the hold can confirm it atomically with the payment.
   */
  public record PaymentSettled(UUID paymentOrderId, UUID customerId, UUID holdId,
                               long amountMinor, String currency) {}
}
