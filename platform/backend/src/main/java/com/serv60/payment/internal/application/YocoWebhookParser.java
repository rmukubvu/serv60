package com.serv60.payment.internal.application;

import com.serv60.payment.api.PaymentContracts;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class YocoWebhookParser {
  private final ObjectMapper json;

  public YocoWebhookParser(ObjectMapper json) {
    this.json = json;
  }

  /**
   * Parses a verified Yoco event. Returns empty for event types this platform does not act on, so
   * the webhook can acknowledge them instead of making the provider retry forever.
   */
  public Optional<PaymentContracts.ProviderPaymentEvent> parse(String eventId, String rawBody) {
    if (eventId == null || eventId.isBlank()) throw new IllegalArgumentException("webhook-id is required");
    try {
      var root = json.readTree(rawBody);
      var data = root.path("data");
      var object = data.path("object").isObject() ? data.path("object") : data;
      var metadata = object.path("metadata").isObject() ? object.path("metadata") : root.path("metadata");
      var orderId = firstText(metadata, "paymentOrderId", "payment_order_id", "orderId");
      var paymentOrderId = orderId == null ? null : UUID.fromString(orderId);
      var reference = firstText(object, "id", "checkoutId", "checkout_id");
      var amount = object.path("amount").asLong(data.path("amount").asLong(0));
      var currency = firstText(object, "currency") == null ? "ZAR" : firstText(object, "currency");
      var eventType = firstText(root, "type", "eventType", "event_type");
      return statusFor(eventType, firstText(object, "status")).map(status ->
          new PaymentContracts.ProviderPaymentEvent("YOCO", eventId, paymentOrderId,
              reference, amount, currency, status));
    } catch (IllegalArgumentException error) {
      throw error;
    } catch (RuntimeException error) {
      throw new IllegalArgumentException("Invalid Yoco webhook payload", error);
    }
  }

  private Optional<String> statusFor(String eventType, String providerStatus) {
    var raw = eventType == null ? providerStatus : eventType;
    if (raw == null) return Optional.empty();
    var value = raw.toLowerCase(Locale.ROOT);
    // Refunds are checked first: "refund.succeeded" must never read as a successful payment.
    if (value.contains("refund")) return Optional.of("REFUNDED");
    if (value.contains("succeed") || value.contains("complete") || value.equals("paid")) return Optional.of("PAID");
    if (value.contains("fail") || value.equals("declined")) return Optional.of("FAILED");
    if (value.contains("cancel")) return Optional.of("CANCELLED");
    return Optional.empty();
  }

  private String firstText(JsonNode node, String... names) {
    for (String name : names) {
      var value = node.path(name);
      if (value.isTextual() && !value.asText().isBlank()) return value.asText();
    }
    return null;
  }
}
