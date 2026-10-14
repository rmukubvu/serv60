package com.serv60.payment.internal.adapters.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.serv60.errors.ApiExceptionHandler;
import com.serv60.payment.api.PaymentContracts;
import com.serv60.payment.internal.application.PaymentService;
import com.serv60.payment.internal.application.YocoWebhookParser;
import com.serv60.payment.internal.application.YocoWebhookVerifier;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class PaymentWebhookControllerTest {
  private static final UUID PAYMENT = UUID.fromString("00000000-0000-0000-0000-000000000098");
  @Mock PaymentService payments;
  @Mock YocoWebhookParser parser;
  @Mock YocoWebhookVerifier verifier;
  private final io.micrometer.core.instrument.simple.SimpleMeterRegistry metrics =
      new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    mvc = MockMvcBuilders.standaloneSetup(new PaymentWebhookController(payments, parser, verifier, metrics))
        .setControllerAdvice(new ApiExceptionHandler()).build();
  }

  @Test
  void verifiesParsesAndReconcilesRawYocoEvent() throws Exception {
    var raw = "{\"type\":\"payment.succeeded\"}";
    var event = new PaymentContracts.ProviderPaymentEvent("YOCO", "evt-1", PAYMENT,
        "ch_1", 24900, "ZAR", "PAID");
    var order = new PaymentContracts.PaymentOrder(PAYMENT, UUID.randomUUID(), UUID.randomUUID(),
        24900, "ZAR", "YOCO", "PAID", "ch_1", "https://checkout.test/ch_1", OffsetDateTime.now());
    when(parser.parse("evt-1", raw)).thenReturn(java.util.Optional.of(event));
    when(payments.reconcile(event, raw)).thenReturn(order);

    mvc.perform(post("/v1/payments/webhooks/yoco")
            .header("webhook-id", "evt-1")
            .header("webhook-timestamp", "1791788400")
            .header("webhook-signature", "v1,signature")
            .contentType("application/json").content(raw))
        .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAID"));
    verify(verifier).verify("evt-1", "1791788400", "v1,signature", raw);
  }

  @Test
  void acknowledgesEventTypesThePlatformIgnoresSoTheProviderStopsRetrying() throws Exception {
    when(parser.parse(eq("evt-3"), any())).thenReturn(java.util.Optional.empty());
    when(parser.type(any())).thenReturn("customer.updated");

    mvc.perform(post("/v1/payments/webhooks/yoco")
            .header("webhook-id", "evt-3")
            .header("webhook-timestamp", "1791788400")
            .header("webhook-signature", "v1,signature")
            .contentType("application/json").content("{\"type\":\"customer.updated\"}"))
        .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("IGNORED"));
    org.mockito.Mockito.verifyNoInteractions(payments);
  }

  @Test
  void countsAFailedRefundSeparatelySoItCanAlarm() throws Exception {
    when(parser.parse(eq("evt-4"), any())).thenReturn(java.util.Optional.empty());
    when(parser.type(any())).thenReturn("refund.failed");

    mvc.perform(post("/v1/payments/webhooks/yoco")
            .header("webhook-id", "evt-4").header("webhook-timestamp", "1791788400")
            .header("webhook-signature", "v1,signature")
            .contentType("application/json").content("{\"type\":\"refund.failed\"}"))
        .andExpect(status().isOk());

    org.assertj.core.api.Assertions.assertThat(
        metrics.counter("serv60.payment.webhooks", "provider", "yoco", "outcome", "refund_failed").count()).isEqualTo(1);
  }

  @Test
  void countsAnUnreconcilableEventAsInvalidNotReconciled() throws Exception {
    var raw = "{\"type\":\"payment.succeeded\"}";
    var event = new PaymentContracts.ProviderPaymentEvent("YOCO", "evt-5", PAYMENT, "ch_1", 1, "ZAR", "PAID");
    when(parser.parse("evt-5", raw)).thenReturn(java.util.Optional.of(event));
    when(payments.reconcile(event, raw)).thenThrow(new IllegalArgumentException("Provider amount does not match payment order"));

    mvc.perform(post("/v1/payments/webhooks/yoco")
            .header("webhook-id", "evt-5").header("webhook-timestamp", "1791788400")
            .header("webhook-signature", "v1,signature")
            .contentType("application/json").content(raw))
        .andExpect(status().isBadRequest());

    org.assertj.core.api.Assertions.assertThat(
        metrics.counter("serv60.payment.webhooks", "provider", "yoco", "outcome", "invalid").count()).isEqualTo(1);
    org.assertj.core.api.Assertions.assertThat(
        metrics.counter("serv60.payment.webhooks", "provider", "yoco", "outcome", "reconciled").count()).isZero();
  }

  @Test
  void rejectsUnverifiedWebhook() throws Exception {
    doThrow(new SecurityException("Invalid Yoco webhook signature"))
        .when(verifier).verify(eq("evt-2"), eq("1791788400"), eq("v1,bad"), any());

    mvc.perform(post("/v1/payments/webhooks/yoco")
            .header("webhook-id", "evt-2")
            .header("webhook-timestamp", "1791788400")
            .header("webhook-signature", "v1,bad")
            .contentType("application/json").content("{}"))
        .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("Invalid Yoco webhook signature"));
    org.assertj.core.api.Assertions.assertThat(
        metrics.counter("serv60.payment.webhooks", "provider", "yoco", "outcome", "rejected").count()).isEqualTo(1);
  }
}
