package com.serv60.payment.internal.adapters.provider;

import com.serv60.payment.api.PaymentContracts;
import com.serv60.payment.api.PaymentGateway;
import com.serv60.payment.api.PaymentProviderUnavailable;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.JsonNode;

/** Yoco hosted-checkout adapter. The secret key remains server-side. */
@Component
public class YocoPaymentGateway implements PaymentGateway {
  private final RestClient client;
  private final String apiKey;
  private final String successUrl;
  private final String cancelUrl;

  @Autowired
  public YocoPaymentGateway(
      @Value("${serv60.payment.yoco.api-base-url:https://payments.yoco.com/api}") String apiBaseUrl,
      @Value("${serv60.payment.yoco.api-key:}") String apiKey,
      @Value("${serv60.payment.yoco.success-url:}") String successUrl,
      @Value("${serv60.payment.yoco.cancel-url:}") String cancelUrl,
      @Value("${serv60.payment.yoco.connect-timeout-ms:3000}") long connectTimeoutMs,
      @Value("${serv60.payment.yoco.read-timeout-ms:10000}") long readTimeoutMs) {
    var httpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(Math.max(1, connectTimeoutMs))).build();
    var requests = new JdkClientHttpRequestFactory(httpClient);
    // Without a read timeout a hung provider pins the request thread indefinitely.
    requests.setReadTimeout(Duration.ofMillis(Math.max(1, readTimeoutMs)));
    this.client = RestClient.builder()
        .baseUrl(apiBaseUrl)
        .requestFactory(requests)
        .build();
    this.apiKey = apiKey;
    this.successUrl = successUrl;
    this.cancelUrl = cancelUrl;
  }

  YocoPaymentGateway(RestClient client, String apiKey, String successUrl, String cancelUrl) {
    this.client = client;
    this.apiKey = apiKey;
    this.successUrl = successUrl;
    this.cancelUrl = cancelUrl;
  }

  @Override
  public String provider() { return "YOCO"; }

  @Override
  public PaymentContracts.Checkout createCheckout(PaymentContracts.PaymentOrder order,
                                                  String idempotencyKey) {
    if (apiKey == null || apiKey.isBlank() || successUrl == null || successUrl.isBlank()
        || cancelUrl == null || cancelUrl.isBlank()) {
      throw new IllegalStateException("Yoco checkout is not configured");
    }
    try {
      var body = Map.of(
          "amount", order.amountMinor(),
          "currency", order.currency(),
          "successUrl", successUrl,
          "cancelUrl", cancelUrl,
          "metadata", Map.of("paymentOrderId", order.id().toString(),
              "holdId", order.holdId().toString()));
      var response = client.post().uri("/checkouts")
          .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
          .header("Idempotency-Key", idempotencyKey)
          .contentType(MediaType.APPLICATION_JSON)
          .body(body)
          .retrieve().body(JsonNode.class);
      if (response == null) throw new com.serv60.payment.api.PaymentProviderUnavailable("Yoco checkout response was empty");
      var reference = text(response, "id");
      var redirectUrl = text(response, "redirectUrl", "redirect_url");
      if (reference == null || redirectUrl == null) {
        throw new com.serv60.payment.api.PaymentProviderUnavailable("Yoco checkout response was incomplete");
      }
      return new PaymentContracts.Checkout(reference, redirectUrl);
    } catch (RestClientResponseException error) {
      throw new PaymentProviderUnavailable(
          "Yoco checkout request failed with status " + error.getStatusCode().value(), error);
    } catch (ResourceAccessException error) {
      throw new PaymentProviderUnavailable("Yoco checkout timed out or was unreachable", error);
    }
  }

  private String text(JsonNode node, String... names) {
    for (var name : names) {
      var value = node.path(name);
      if (value.isTextual() && !value.asText().isBlank()) return value.asText();
    }
    return null;
  }
}
