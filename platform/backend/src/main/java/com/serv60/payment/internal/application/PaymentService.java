package com.serv60.payment.internal.application;

import com.serv60.payment.api.PaymentContracts;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Stores the payment boundary without coupling booking to a gateway SDK. */
@Service
public class PaymentService {
  private static final String ZAR = "ZAR";
  private static final java.util.Set<String> SUPPORTED_PROVIDERS =
      java.util.Set.of("YOCO", "PEACH", "PAYFAST");

  private final JdbcClient db;
  private final Clock clock;

  public PaymentService(JdbcClient db, Clock clock) {
    this.db = db;
    this.clock = clock;
  }

  @Transactional
  public PaymentContracts.PaymentOrder create(PaymentContracts.CreatePayment request, String key) {
    if (key == null || key.isBlank()) throw new IllegalArgumentException("Idempotency-Key is required");
    var provider = normalizeProvider(request.provider());
    var currency = normalizeCurrency(request.currency());
    if (request.amountMinor() <= 0) throw new IllegalArgumentException("amountMinor must be positive");

    var requestHash = requestHash(request, provider, currency);
    var existing = db.sql("select * from payment_order where customer_id=:customer and idempotency_key=:key")
        .param("customer", request.customerId()).param("key", key)
        .query(this::map).optional();
    if (existing.isPresent()) {
      var existingHash = db.sql("select request_hash from payment_order where id=:id")
          .param("id", existing.get().id()).query(String.class).single();
      if (!requestHash.equals(existingHash)) {
        throw new IllegalStateException("Idempotency-Key was reused with a different request");
      }
      return existing.get();
    }

    var hold = db.sql("select customer_id, status from visit_hold where id=:id")
        .param("id", request.holdId()).query((rs, row) -> new HoldRow(
            rs.getObject("customer_id", UUID.class), rs.getString("status"))).optional()
        .orElseThrow(() -> new IllegalArgumentException("Visit hold not found"));
    if (!hold.customerId().equals(request.customerId())) {
      throw new IllegalArgumentException("Payment customer does not own the visit hold");
    }
    if (!hold.status().equals("HELD") && !hold.status().equals("CONFIRMED")) {
      throw new IllegalStateException("Visit hold is not payable");
    }

    var id = UUID.randomUUID();
    var now = OffsetDateTime.now(clock);
    db.sql("""
        insert into payment_order(id, customer_id, hold_id, amount_minor, currency, provider,
          status, idempotency_key, request_hash, created_at, updated_at)
        values (:id,:customer,:hold,:amount,:currency,:provider,'PENDING',:key,:hash,:created,:created)
        """)
        .param("id", id).param("customer", request.customerId()).param("hold", request.holdId())
        .param("amount", request.amountMinor()).param("currency", currency).param("provider", provider)
        .param("key", key).param("hash", requestHash).param("created", now).update();
    return new PaymentContracts.PaymentOrder(id, request.customerId(), request.holdId(),
        request.amountMinor(), currency, provider, "PENDING", null, now);
  }

  public Optional<PaymentContracts.PaymentOrder> find(UUID id) {
    return db.sql("select * from payment_order where id=:id").param("id", id)
        .query(this::map).optional();
  }

  private String normalizeProvider(String raw) {
    if (raw == null || raw.isBlank()) throw new IllegalArgumentException("provider is required");
    var provider = raw.trim().toUpperCase(Locale.ROOT);
    if (!SUPPORTED_PROVIDERS.contains(provider)) {
      throw new IllegalArgumentException("Unsupported payment provider: " + provider);
    }
    return provider;
  }

  private String normalizeCurrency(String raw) {
    if (raw == null || raw.isBlank()) throw new IllegalArgumentException("currency is required");
    var currency = raw.trim().toUpperCase(Locale.ROOT);
    if (!currency.equals(ZAR)) throw new IllegalArgumentException("Only ZAR payments are enabled");
    return currency;
  }

  private String requestHash(PaymentContracts.CreatePayment request, String provider, String currency) {
    var canonical = String.join("\u001f", request.customerId().toString(), request.holdId().toString(),
        Long.toString(request.amountMinor()), currency, provider);
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
          .digest(canonical.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.NoSuchAlgorithmException error) {
      throw new IllegalStateException("SHA-256 is unavailable", error);
    }
  }

  private PaymentContracts.PaymentOrder map(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
    return new PaymentContracts.PaymentOrder(rs.getObject("id", UUID.class),
        rs.getObject("customer_id", UUID.class), rs.getObject("hold_id", UUID.class),
        rs.getLong("amount_minor"), rs.getString("currency"), rs.getString("provider"),
        rs.getString("status"), rs.getString("provider_reference"),
        rs.getObject("created_at", OffsetDateTime.class));
  }

  private record HoldRow(UUID customerId, String status) {}
}
