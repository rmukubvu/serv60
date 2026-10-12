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
                             String providerReference, OffsetDateTime createdAt) {}
}
