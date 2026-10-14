package com.serv60.payment.internal.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

@Component
public class YocoWebhookVerifier {
  private final Clock clock;
  private final String secret;
  private final Duration maxSkew;

  public YocoWebhookVerifier(Clock clock,
                             @Value("${serv60.payment.yoco.webhook-secret:}") String secret,
                             @Value("${serv60.payment.yoco.webhook-max-skew-seconds:180}") long maxSkewSeconds) {
    this.clock = clock;
    this.secret = secret == null ? "" : secret.trim();
    this.maxSkew = Duration.ofSeconds(maxSkewSeconds);
  }

  public void verify(String webhookId, String timestamp, String signature, String rawBody) {
    // A deployment fault, not a bad caller: 500 makes Yoco retry and the error alarm fire.
    if (secret.isBlank()) throw new IllegalStateException("Yoco webhook secret is not configured");
    if (webhookId == null || timestamp == null || signature == null || rawBody == null) {
      throw new SecurityException("Yoco webhook headers are incomplete");
    }
    long epoch;
    try { epoch = Long.parseLong(timestamp); }
    catch (NumberFormatException error) { throw new SecurityException("Invalid webhook timestamp", error); }
    var age = Math.abs(Instant.now(clock).getEpochSecond() - epoch);
    if (age > maxSkew.toSeconds()) throw new SecurityException("Yoco webhook timestamp is outside the allowed window");
    var content = webhookId + "." + timestamp + "." + rawBody;
    var expected = signature(content);
    for (String candidate : signature.split(" ")) {
      var parts = candidate.split(",", 2);
      if (parts.length == 2 && parts[0].equals("v1")
          && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), parts[1].getBytes(StandardCharsets.UTF_8))) return;
    }
    throw new SecurityException("Invalid Yoco webhook signature");
  }

  private String signature(String content) {
    try {
      var encodedSecret = secret.startsWith("whsec_") ? secret.substring("whsec_".length()) : secret;
      var key = Base64.getDecoder().decode(encodedSecret);
      var mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      return Base64.getEncoder().encodeToString(mac.doFinal(content.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception error) {
      throw new SecurityException("Invalid Yoco webhook secret", error);
    }
  }
}
