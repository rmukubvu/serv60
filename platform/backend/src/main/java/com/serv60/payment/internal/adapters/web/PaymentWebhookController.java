package com.serv60.payment.internal.adapters.web;

import com.serv60.payment.internal.application.PaymentService;
import com.serv60.payment.internal.application.YocoWebhookParser;
import com.serv60.payment.internal.application.YocoWebhookVerifier;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/payments/webhooks")
public class PaymentWebhookController {
  private final PaymentService payments;
  private final YocoWebhookParser parser;
  private final YocoWebhookVerifier verifier;
  private final io.micrometer.core.instrument.MeterRegistry metrics;

  public PaymentWebhookController(PaymentService payments, YocoWebhookParser parser,
                                  YocoWebhookVerifier verifier,
                                  io.micrometer.core.instrument.MeterRegistry metrics) {
    this.payments = payments;
    this.parser = parser;
    this.verifier = verifier;
    this.metrics = metrics;
  }

  /** Reconciles a verified event; acknowledges event types the platform ignores with 200. */
  @PostMapping("/yoco")
  @ResponseStatus(HttpStatus.OK)
  public Object yoco(
      @RequestHeader("webhook-id") String webhookId,
      @RequestHeader("webhook-timestamp") String timestamp,
      @RequestHeader("webhook-signature") String signature,
      @RequestBody String rawBody) {
    try {
      verifier.verify(webhookId, timestamp, signature, rawBody);
    } catch (SecurityException rejected) {
      // A burst of rejections means a rotated secret or someone probing the endpoint.
      count("rejected");
      throw rejected;
    }
    var event = parser.parse(webhookId, rawBody);
    count(event.isPresent() ? "reconciled" : "ignored");
    return event.<Object>map(e -> payments.reconcile(e, rawBody))
        .orElseGet(() -> java.util.Map.of("status", "IGNORED"));
  }

  private void count(String outcome) {
    metrics.counter("serv60.payment.webhooks", "provider", "yoco", "outcome", outcome).increment();
  }
}
