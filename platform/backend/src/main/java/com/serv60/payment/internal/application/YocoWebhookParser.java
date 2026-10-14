package com.serv60.payment.internal.application;

import com.serv60.payment.api.PaymentContracts;
import com.serv60.payment.api.PaymentStatus;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Parses Yoco webhook events in the documented shape:
 * {@code {"id","type","payload":{"id","amount","currency","metadata":{"checkoutId",...}}}}.
 * See https://developer.yoco.com/online/api-reference/webhooks/events/successful-payment.
 */
@Component
public class YocoWebhookParser {
  /** Only documented event types change an order. Anything else, including refund.failed, does not. */
  private static final Map<String, PaymentStatus> STATUS_BY_TYPE = Map.of(
      "payment.succeeded", PaymentStatus.PAID,
      "payment.failed", PaymentStatus.FAILED,
      "payment.cancelled", PaymentStatus.CANCELLED,
      "refund.succeeded", PaymentStatus.REFUNDED);

  private final ObjectMapper json;

  public YocoWebhookParser(ObjectMapper json) {
    this.json = json;
  }

  /** The event type, for logging and metrics on events the platform does not act on. */
  public String type(String rawBody) {
    try {
      var type = json.readTree(rawBody).path("type");
      return type.isTextual() ? type.asText() : "unknown";
    } catch (RuntimeException error) {
      return "unreadable";
    }
  }

  /**
   * Returns the order change a verified event asks for, or empty for event types the platform does
   * not act on, so the webhook can acknowledge them instead of making Yoco retry forever.
   */
  public Optional<PaymentContracts.ProviderPaymentEvent> parse(String eventId, String rawBody) {
    if (eventId == null || eventId.isBlank()) throw new IllegalArgumentException("webhook-id is required");
    JsonNode root;
    try {
      root = json.readTree(rawBody);
    } catch (RuntimeException error) {
      throw new IllegalArgumentException("Invalid Yoco webhook payload", error);
    }
    var status = STATUS_BY_TYPE.get(text(root, "type"));
    if (status == null) return Optional.empty();
    var payload = root.path("payload");
    if (!payload.isObject()) throw new IllegalArgumentException("Yoco webhook has no payload");
    var metadata = payload.path("metadata");
    var orderId = text(metadata, "paymentOrderId");
    var amount = payload.path("amount");
    if (!amount.canConvertToLong()) throw new IllegalArgumentException("Yoco webhook has no amount");
    var currency = text(payload, "currency");
    return Optional.of(new PaymentContracts.ProviderPaymentEvent(
        "YOCO", eventId,
        orderId == null ? null : uuid(orderId),
        // The order stores Yoco's checkout id (ch_...); payload.id is the payment id (p_...).
        text(metadata, "checkoutId"),
        amount.asLong(),
        currency == null ? "ZAR" : currency,
        status.name()));
  }

  private static UUID uuid(String value) {
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException error) {
      throw new IllegalArgumentException("Yoco webhook paymentOrderId is not a UUID", error);
    }
  }

  private static String text(JsonNode node, String name) {
    var value = node.path(name);
    return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
  }
}
