package com.serv60.payment.internal.adapters.web;

import com.serv60.payment.api.PaymentContracts;
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

  @PostMapping("/yoco")
  @ResponseStatus(HttpStatus.OK)
  public PaymentContracts.PaymentOrder yoco(
      @RequestHeader("webhook-id") String webhookId,
      @RequestHeader("webhook-timestamp") String timestamp,
      @RequestHeader("webhook-signature") String signature,
      @RequestBody String rawBody) {
    verifier.verify(webhookId, timestamp, signature, rawBody);
    var event = parser.parse(webhookId, rawBody);
    return payments.reconcile(event, rawBody);
  }
}
