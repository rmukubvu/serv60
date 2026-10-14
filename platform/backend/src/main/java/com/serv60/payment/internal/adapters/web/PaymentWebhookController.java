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

  public PaymentWebhookController(PaymentService payments, YocoWebhookParser parser,
                                  YocoWebhookVerifier verifier) {
    this.payments = payments;
    this.parser = parser;
    this.verifier = verifier;
  }

  /** Reconciles a verified event; acknowledges event types the platform ignores with 200. */
  @PostMapping("/yoco")
  @ResponseStatus(HttpStatus.OK)
  public Object yoco(
      @RequestHeader("webhook-id") String webhookId,
      @RequestHeader("webhook-timestamp") String timestamp,
      @RequestHeader("webhook-signature") String signature,
      @RequestBody String rawBody) {
    verifier.verify(webhookId, timestamp, signature, rawBody);
    return parser.parse(webhookId, rawBody)
        .<Object>map(event -> payments.reconcile(event, rawBody))
        .orElseGet(() -> java.util.Map.of("status", "IGNORED"));
  }
}
