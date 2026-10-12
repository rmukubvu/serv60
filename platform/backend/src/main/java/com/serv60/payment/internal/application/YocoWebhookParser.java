package com.serv60.payment.internal.application;

import com.serv60.payment.api.PaymentContracts;
import java.util.Locale;
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

  public PaymentContracts.ProviderPaymentEvent parse(String eventId, String rawBody) {
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
      var status = statusFor(eventType, firstText(object, "status"));
      return new PaymentContracts.ProviderPaymentEvent("YOCO", eventId, paymentOrderId,
          reference, amount, currency, status);
    } catch (IllegalArgumentException error) {
      throw error;
    } catch (RuntimeException error) {
      throw new IllegalArgumentException("Invalid Yoco webhook payload", error);
    }
  }

  private String statusFor(String eventType, String providerStatus) {
    var value = (eventType == null ? providerStatus : eventType).toLowerCase(Locale.ROOT);
    if (value.contains("succeed") || value.contains("complete") || value.equals("paid")) return "PAID";
    if (value.contains("fail") || value.equals("declined")) return "FAILED";
    if (value.contains("cancel") || value.contains("refund")) return "CANCELLED";
    throw new IllegalArgumentException("Unsupported Yoco payment event");
  }

  private String firstText(JsonNode node, String... names) {
    for (String name : names) {
      var value = node.path(name);
      if (value.isTextual() && !value.asText().isBlank()) return value.asText();
    }
    return null;
  }
}
