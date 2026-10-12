package com.serv60.payment.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.serv60.payment.api.PaymentContracts;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class YocoWebhookParserTest {
  private final YocoWebhookParser parser = new YocoWebhookParser(new ObjectMapper());
  private static final UUID ORDER = UUID.fromString("00000000-0000-0000-0000-000000000098");

  @Test
  void parsesSuccessfulCheckoutMetadataAndAmount() {
    var event = parser.parse("evt-1", """
        {"type":"payment.succeeded","data":{"object":{"id":"ch_123","amount":24900,"currency":"ZAR","metadata":{"paymentOrderId":"00000000-0000-0000-0000-000000000098"}}}}
        """);

    assertThat(event).extracting(PaymentContracts.ProviderPaymentEvent::provider,
        PaymentContracts.ProviderPaymentEvent::eventId, PaymentContracts.ProviderPaymentEvent::paymentOrderId,
        PaymentContracts.ProviderPaymentEvent::providerReference, PaymentContracts.ProviderPaymentEvent::amountMinor,
        PaymentContracts.ProviderPaymentEvent::currency, PaymentContracts.ProviderPaymentEvent::status)
        .containsExactly("YOCO", "evt-1", ORDER, "ch_123", 24900L, "ZAR", "PAID");
  }

  @Test
  void mapsFailedAndCancelledEvents() {
    assertThat(parser.parse("failed", "{\"type\":\"payment.failed\",\"data\":{\"id\":\"ch-1\",\"amount\":100}}")
        .status()).isEqualTo("FAILED");
    assertThat(parser.parse("cancelled", "{\"eventType\":\"checkout.cancelled\",\"data\":{\"id\":\"ch-2\",\"amount\":100}}")
        .status()).isEqualTo("CANCELLED");
  }

  @Test
  void rejectsUnknownEventsAndMalformedOrderIds() {
    assertThatThrownBy(() -> parser.parse("evt", "{\"type\":\"customer.updated\"}"))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Unsupported");
    assertThatThrownBy(() -> parser.parse("evt", "{\"type\":\"payment.succeeded\",\"data\":{\"metadata\":{\"paymentOrderId\":\"bad\"}}}"))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
