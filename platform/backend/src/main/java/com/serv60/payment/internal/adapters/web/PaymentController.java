package com.serv60.payment.internal.adapters.web;

import com.serv60.payment.api.PaymentContracts;
import com.serv60.payment.internal.application.PaymentService;
import com.serv60.payment.internal.application.PaymentCheckoutService;
import com.serv60.security.CustomerAuthorization;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.validation.annotation.Validated;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

@RestController
@RequestMapping("/v1/payments")
@Validated
public class PaymentController {
  private final PaymentService payments;
  private final PaymentCheckoutService checkout;
  private final CustomerAuthorization customerAuthorization;

  public PaymentController(PaymentService payments, PaymentCheckoutService checkout,
                           CustomerAuthorization customerAuthorization) {
    this.payments = payments;
    this.checkout = checkout;
    this.customerAuthorization = customerAuthorization;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public PaymentContracts.PaymentOrder create(
      @RequestHeader("Idempotency-Key") @NotBlank String key,
      @Valid @RequestBody PaymentContracts.CreatePayment request) {
    customerAuthorization.require(request.customerId(), currentAuthentication());
    return payments.create(request, key);
  }

  @GetMapping("/{id}")
  public PaymentContracts.PaymentOrder find(@PathVariable UUID id) {
    var payment = payments.find(id).orElseThrow(() ->
        new com.serv60.errors.NotFound("Payment order not found"));
    customerAuthorization.require(payment.customerId(), currentAuthentication());
    return payment;
  }

  @PostMapping("/{id}/checkout")
  public PaymentContracts.PaymentOrder checkout(@PathVariable UUID id,
                                                @RequestHeader("Idempotency-Key") @NotBlank String key) {
    var payment = payments.find(id).orElseThrow(() ->
        new com.serv60.errors.NotFound("Payment order not found"));
    customerAuthorization.require(payment.customerId(), currentAuthentication());
    return checkout.start(id, key);
  }

  @org.springframework.web.bind.annotation.ExceptionHandler(
      com.serv60.payment.api.PaymentProviderUnavailable.class)
  org.springframework.http.ResponseEntity<java.util.Map<String, String>> providerUnavailable(
      com.serv60.payment.api.PaymentProviderUnavailable error) {
    return org.springframework.http.ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
        .header("Retry-After", "30")
        .body(java.util.Map.of("error", "Payment is temporarily unavailable. Please try again shortly"));
  }

  private Authentication currentAuthentication() {
    return SecurityContextHolder.getContext().getAuthentication();
  }
}
