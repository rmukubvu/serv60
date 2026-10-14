package com.serv60.payment.internal.adapters.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.serv60.payment.api.PaymentContracts;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class YocoPaymentGatewayTest {
  private static final UUID PAYMENT = UUID.fromString("00000000-0000-0000-0000-000000000098");
  private static final UUID HOLD = UUID.fromString("00000000-0000-0000-0000-000000000099");

  @Test
  void createsHostedCheckoutWithServerSideHeadersAndMetadata() {
    var builder = RestClient.builder().baseUrl("https://payments.test/api");
    var server = MockRestServiceServer.bindTo(builder).build();
    var client = builder.build();
    server.expect(requestTo("https://payments.test/api/checkouts"))
        .andExpect(method(HttpMethod.POST))
        .andExpect(header("Authorization", "Bearer sk_test"))
        .andExpect(header("Idempotency-Key", "checkout-key"))
        .andExpect(jsonPath("$.amount").value(24900))
        .andExpect(jsonPath("$.currency").value("ZAR"))
        .andExpect(jsonPath("$.metadata.paymentOrderId").value(PAYMENT.toString()))
        .andRespond(withSuccess("""
            {"id":"ch_123","redirectUrl":"https://checkout.test/ch_123"}
            """, MediaType.APPLICATION_JSON));

    var result = new YocoPaymentGateway(client, "sk_test", "https://app.test/success",
        "https://app.test/cancel").createCheckout(order(), "checkout-key");

    assertThat(result.providerReference()).isEqualTo("ch_123");
    assertThat(result.redirectUrl()).isEqualTo("https://checkout.test/ch_123");
    server.verify();
  }

  @Test
  void rejectsMissingConfigurationBeforeCallingProvider() {
    var client = RestClient.builder().baseUrl("https://payments.test/api").build();
    var gateway = new YocoPaymentGateway(client, "", "https://app.test/success", "https://app.test/cancel");
    assertThatThrownBy(() -> gateway.createCheckout(order(), "checkout-key"))
        .isInstanceOf(IllegalStateException.class).hasMessage("Yoco checkout is not configured");
  }

  @Test
  void rejectsIncompleteProviderResponse() {
    var builder = RestClient.builder().baseUrl("https://payments.test/api");
    var server = MockRestServiceServer.bindTo(builder).build();
    var client = builder.build();
    server.expect(requestTo("https://payments.test/api/checkouts"))
        .andRespond(withSuccess("{\"id\":\"ch_123\"}", MediaType.APPLICATION_JSON));
    var gateway = new YocoPaymentGateway(client, "sk_test", "https://app.test/success",
        "https://app.test/cancel");
    assertThatThrownBy(() -> gateway.createCheckout(order(), "checkout-key"))
        .isInstanceOf(IllegalStateException.class).hasMessage("Yoco checkout response was incomplete");
  }

  @Test
  void translatesProviderServerErrorsIntoARetryableFailure() {
    var builder = RestClient.builder().baseUrl("https://payments.test/api");
    var server = MockRestServiceServer.bindTo(builder).build();
    server.expect(requestTo("https://payments.test/api/checkouts"))
        .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withServerError());
    var gateway = new YocoPaymentGateway(builder.build(), "sk_test", "https://app.test/success",
        "https://app.test/cancel");

    assertThatThrownBy(() -> gateway.createCheckout(order(), "checkout-key"))
        .isInstanceOf(com.serv60.payment.api.PaymentProviderUnavailable.class)
        .hasMessageContaining("500");
  }

  @Test
  void givesUpOnAHungProviderAtTheReadTimeout() throws Exception {
    var hung = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    var release = new java.util.concurrent.CountDownLatch(1);
    hung.createContext("/api/checkouts", exchange -> {
      try {
        release.await(10, java.util.concurrent.TimeUnit.SECONDS);
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
      }
      exchange.close();
    });
    hung.start();
    try {
      var gateway = new YocoPaymentGateway("http://127.0.0.1:" + hung.getAddress().getPort() + "/api",
          "sk_test", "https://app.test/success", "https://app.test/cancel", 1000, 300);
      long started = System.nanoTime();

      assertThatThrownBy(() -> gateway.createCheckout(order(), "checkout-key"))
          .isInstanceOf(com.serv60.payment.api.PaymentProviderUnavailable.class)
          .hasMessageContaining("timed out");
      assertThat(java.time.Duration.ofNanos(System.nanoTime() - started)).isLessThan(java.time.Duration.ofSeconds(5));
    } finally {
      release.countDown();
      hung.stop(0);
    }
  }

  private PaymentContracts.PaymentOrder order() {
    return new PaymentContracts.PaymentOrder(PAYMENT, UUID.randomUUID(), HOLD, 24900, "ZAR", "YOCO",
        "PENDING", null, null, OffsetDateTime.parse("2026-10-12T08:00:00Z"));
  }
}
