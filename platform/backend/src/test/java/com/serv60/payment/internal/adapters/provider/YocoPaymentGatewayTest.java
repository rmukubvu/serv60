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
    var client = RestClient.builder().baseUrl("https://payments.test/api").build();
    var server = MockRestServiceServer.bindTo(client).build();
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
    var client = RestClient.builder().baseUrl("https://payments.test/api").build();
    var server = MockRestServiceServer.bindTo(client).build();
    server.expect(requestTo("https://payments.test/api/checkouts"))
        .andRespond(withSuccess("{\"id\":\"ch_123\"}", MediaType.APPLICATION_JSON));
    var gateway = new YocoPaymentGateway(client, "sk_test", "https://app.test/success",
        "https://app.test/cancel");
    assertThatThrownBy(() -> gateway.createCheckout(order(), "checkout-key"))
        .isInstanceOf(IllegalStateException.class).hasMessage("Yoco checkout response was incomplete");
  }

  private PaymentContracts.PaymentOrder order() {
    return new PaymentContracts.PaymentOrder(PAYMENT, UUID.randomUUID(), HOLD, 24900, "ZAR", "YOCO",
        "PENDING", null, null, OffsetDateTime.parse("2026-10-12T08:00:00Z"));
  }
}
