package com.serv60.observability;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import com.serv60.support.SharedPostgres;
import org.springframework.test.context.ContextConfiguration;

/** Proves an error logged while serving a real HTTP request carries its trace and request ids. */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "serv60.booking.expiry-sweep-enabled=false",
      "serv60.roadside.expiry-sweep-enabled=false",
      "serv60.security.enabled=false",
      "management.tracing.sampling.probability=1.0"
    })
@ExtendWith(OutputCaptureExtension.class)
@ContextConfiguration(initializers = SharedPostgres.class)
class CorrelationIntegrationTest {
  @LocalServerPort int port;

  @Test
  void shouldLogTheTraceAndRequestIdWithAnErrorRaisedDuringARequest(CapturedOutput output)
      throws Exception {
    // No webhook secret is configured in tests, so this request logs an internal error.
    var request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/payments/webhooks/yoco"))
            .header("X-Request-Id", "corr-test-42")
            .header("webhook-id", "evt-1")
            .header("webhook-timestamp", "1")
            .header("webhook-signature", "v1,x")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{}"))
            .build();

    var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(500);
    assertThat(response.headers().firstValue(RequestCorrelationFilter.HEADER)).hasValue("corr-test-42");
    assertThat(output.getOut())
        .containsPattern("\\[[0-9a-f]{32} corr-test-42\\] .*Unexpected state error");
  }
}
