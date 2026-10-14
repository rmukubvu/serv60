package com.serv60.payment.internal.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class YocoWebhookVerifierTest {
  private static final String SECRET = "whsec_" + Base64.getEncoder().encodeToString("test-secret".getBytes(StandardCharsets.UTF_8));
  private static final Instant NOW = Instant.parse("2026-10-12T08:00:00Z");
  private final YocoWebhookVerifier verifier = new YocoWebhookVerifier(
      Clock.fixed(NOW, ZoneOffset.UTC), SECRET, 180);

  @Test
  void acceptsValidSignatureAndOneOfMultipleSignatures() throws Exception {
    var timestamp = Long.toString(NOW.getEpochSecond());
    var body = "{\"id\":\"ch_1\"}";
    var valid = sign("evt-1", timestamp, body);
    assertThatCode(() -> verifier.verify("evt-1", timestamp, "v1,invalid v1," + valid, body))
        .doesNotThrowAnyException();
  }

  @Test
  void rejectsStaleMissingAndInvalidSignatures() throws Exception {
    var timestamp = Long.toString(NOW.getEpochSecond());
    var body = "{}";
    assertThatThrownBy(() -> verifier.verify("evt", Long.toString(NOW.minusSeconds(181).getEpochSecond()),
        "v1," + sign("evt", timestamp, body), body))
        .isInstanceOf(SecurityException.class).hasMessageContaining("outside");
    assertThatThrownBy(() -> verifier.verify("evt", timestamp, "v1,bad", body))
        .isInstanceOf(SecurityException.class).hasMessageContaining("signature");
    var unconfigured = new YocoWebhookVerifier(Clock.fixed(NOW, ZoneOffset.UTC), "", 180);
    assertThatThrownBy(() -> unconfigured.verify("evt", timestamp, "v1,bad", body))
        .isInstanceOf(IllegalStateException.class).hasMessageContaining("not configured");
  }

  private String sign(String id, String timestamp, String body) throws Exception {
    var mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec("test-secret".getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return Base64.getEncoder().encodeToString(mac.doFinal((id + "." + timestamp + "." + body)
        .getBytes(StandardCharsets.UTF_8)));
  }
}
