package com.serv60.payment.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.serv60.payment.api.PaymentContracts.ProviderPaymentEvent;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.ObjectMapper;

/** Fixtures follow Yoco's documented event shape: the payment sits under "payload". */
class YocoWebhookParserTest {
  private final YocoWebhookParser parser = new YocoWebhookParser(new ObjectMapper());
  private static final UUID ORDER = UUID.fromString("00000000-0000-0000-0000-000000000098");

  static String event(String type, long amount) {
    return """
        {"createdDate":"2026-10-14T08:00:00Z","id":"evt_1","type":"%s",
         "payload":{"id":"p_123","type":"payment","status":"succeeded","mode":"test",
                    "amount":%d,"currency":"ZAR",
                    "metadata":{"checkoutId":"ch_123","paymentOrderId":"%s"}}}
        """.formatted(type, amount, ORDER);
  }

  @Test
  void shouldReadASuccessfulPaymentFromThePayloadAndKeyItByCheckout() {
    var event = parser.parse("evt-1", event("payment.succeeded", 24900)).orElseThrow();

    assertThat(event).isEqualTo(new ProviderPaymentEvent("YOCO", "evt-1", ORDER, "ch_123", 24900, "ZAR", "PAID"));
  }

  @Test
  void shouldMapAFullRefundToRefunded() {
    var event = parser.parse("evt-2", event("refund.succeeded", 24900)).orElseThrow();

    assertThat(event.status()).isEqualTo("REFUNDED");
  }

  @ParameterizedTest
  @CsvSource({"refund.failed", "customer.updated", "payment.requires_action"})
  void shouldIgnoreEventsThatDoNotChangeAnOrder(String type) {
    assertThat(parser.parse("evt-3", event(type, 24900))).isEmpty();
  }

  @Test
  void shouldReportTheEventTypeEvenWhenIgnored() {
    assertThat(parser.type(event("refund.failed", 1))).isEqualTo("refund.failed");
  }

  @Test
  void shouldRejectAPaymentEventWithoutAPayloadAmount() {
    assertThatThrownBy(() -> parser.parse("evt-4", "{\"type\":\"payment.succeeded\",\"payload\":{\"currency\":\"ZAR\"}}"))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("amount");
  }

  @Test
  void shouldRejectAMalformedOrderId() {
    var body = event("payment.succeeded", 100).replace(ORDER.toString(), "bad");

    assertThatThrownBy(() -> parser.parse("evt-5", body)).isInstanceOf(IllegalArgumentException.class);
  }
}
