package com.serv60.payment.internal.application;

import com.serv60.payment.api.PaymentContracts;
import com.serv60.payment.api.PaymentStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Stores the payment boundary without coupling booking to a gateway SDK. */
@Service
public class PaymentService implements com.serv60.payment.api.PaymentOrders {
  private static final String ZAR = "ZAR";
  private static final java.util.Set<String> SUPPORTED_PROVIDERS =
      java.util.Set.of("YOCO", "PEACH", "PAYFAST");

  private final JdbcClient db;
  private final Clock clock;
  private final ApplicationEventPublisher events;

  public PaymentService(JdbcClient db, Clock clock, ApplicationEventPublisher events) {
    this.db = db;
    this.clock = clock;
    this.events = events;
  }

  @Transactional
  public PaymentContracts.PaymentOrder create(PaymentContracts.CreatePayment request, String key) {
    if (key == null || key.isBlank()) throw new IllegalArgumentException("Idempotency-Key is required");
    var provider = normalizeProvider(request.provider());
    var currency = normalizeCurrency(request.currency());
    if (request.amountMinor() <= 0) throw new IllegalArgumentException("amountMinor must be positive");

    var requestHash = requestHash(request, provider, currency);
    // Serialize same-key retries so the second waits and replays instead of hitting the unique key.
    db.sql("select pg_advisory_xact_lock(hashtextextended(:lock,0))")
        .param("lock", "payment:" + request.customerId() + ":" + key).query((rs, row) -> true).single();
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
        request.amountMinor(), currency, provider, PaymentStatus.PENDING.name(), null, null, now);
  }

  /** Rejects checkout for an expired, released or missing reservation before contacting the provider. */
  public void requirePayable(PaymentContracts.PaymentOrder order) {
    boolean payable=db.sql("select exists(select 1 from visit_hold where id=:id and customer_id=:customer and (status='CONFIRMED' or (status='HELD' and expires_at>:now)))")
        .param("id",order.holdId()).param("customer",order.customerId()).param("now",OffsetDateTime.now(clock)).query(Boolean.class).single();
    if (!payable) throw new IllegalStateException("This reservation is no longer payable. Please choose a new time");
  }

  public Optional<PaymentContracts.PaymentOrder> find(UUID id) {
    return db.sql("select * from payment_order where id=:id").param("id", id)
        .query(this::map).optional();
  }

  @Transactional
  public PaymentContracts.PaymentOrder attachCheckout(UUID id, PaymentContracts.Checkout checkout) {
    if (checkout == null || checkout.providerReference() == null || checkout.providerReference().isBlank()) {
      throw new IllegalArgumentException("Checkout provider reference is required");
    }
    if (checkout.redirectUrl() == null || checkout.redirectUrl().isBlank()) {
      throw new IllegalArgumentException("Checkout redirect URL is required");
    }
    var updated = db.sql("""
        update payment_order
           set provider_reference=:reference, checkout_url=:url, updated_at=:updated
         where id=:id and status='PENDING' and checkout_url is null
        """)
        .param("reference", checkout.providerReference()).param("url", checkout.redirectUrl())
        .param("updated", OffsetDateTime.now(clock)).param("id", id).update();
    if (updated == 0) {
      return find(id).orElseThrow(() -> new IllegalArgumentException("Payment order not found"));
    }
    return find(id).orElseThrow();
  }

  @Transactional
  public PaymentContracts.PaymentOrder reconcile(PaymentContracts.ProviderPaymentEvent event,
                                                 String rawPayload) {
    var provider = normalizeProvider(event.provider());
    if (event.eventId() == null || event.eventId().isBlank()) {
      throw new IllegalArgumentException("Provider event id is required");
    }
    if (event.amountMinor() <= 0) throw new IllegalArgumentException("Provider amount must be positive");
    var currency = normalizeCurrency(event.currency());
    var reported = PaymentStatus.of(event.status()).orElseThrow(() ->
        new IllegalArgumentException("Unsupported payment status: " + event.status()));

    var receipt = db.sql("select payment_order_id from payment_webhook_receipt where provider=:provider and event_id=:event")
        .param("provider", provider).param("event", event.eventId())
        .query(UUID.class).optional();
    if (receipt.isPresent()) return find(receipt.get()).orElseThrow();

    var order = findForUpdate(event, provider).orElseThrow(() ->
        new IllegalArgumentException("Payment order for provider event was not found"));
    if (!order.provider().equals(provider)) throw new IllegalArgumentException("Provider does not match payment order");
    boolean refund = reported.refunded();
    boolean amountMatches = refund
        ? event.amountMinor() <= order.amountMinor()
        : event.amountMinor() == order.amountMinor();
    if (!amountMatches || !order.currency().equals(currency)) {
      throw new IllegalArgumentException("Provider amount does not match payment order");
    }
    var eventStatus = !refund ? reported
        : event.amountMinor() < order.amountMinor() ? PaymentStatus.PARTIALLY_REFUNDED : PaymentStatus.REFUNDED;
    var receivedAt = OffsetDateTime.now(clock);
    var inserted = db.sql("""
        insert into payment_webhook_receipt(id, provider, event_id, payment_order_id, payload, received_at)
        values (:id,:provider,:event,:order,cast(:payload as jsonb),:received)
        on conflict (provider, event_id) do nothing
        """)
        .param("id", UUID.randomUUID()).param("provider", provider).param("event", event.eventId())
        .param("order", order.id()).param("payload", rawPayload).param("received", receivedAt).update();
    if (inserted == 0) return find(order.id()).orElseThrow();

    var current = PaymentStatus.valueOf(order.status());
    var nextStatus = current.canAdvanceTo(eventStatus) ? eventStatus : current;
    db.sql("update payment_order set status=:status, provider_reference=coalesce(provider_reference,:reference), updated_at=:updated where id=:id")
        .param("status", nextStatus.name()).param("reference", event.providerReference())
        .param("updated", receivedAt).param("id", order.id()).update();
    if (nextStatus == PaymentStatus.PAID && current != PaymentStatus.PAID) {
      // Listeners run in this transaction: the payment and the confirmed visit commit together.
      events.publishEvent(new PaymentContracts.PaymentSettled(order.id(), order.customerId(),
          order.holdId(), order.amountMinor(), order.currency()));
    }
    if (nextStatus.refunded() && nextStatus != current) {
      // A refund never silently cancels a visit: operations decide, prompted by this event.
      db.sql("insert into outbox_event(id,event_type,aggregate_id,payload) values (:id,'PaymentRefunded',:aggregate,cast(:payload as jsonb))")
          .param("id", UUID.randomUUID()).param("aggregate", order.holdId())
          .param("payload", "{\"paymentOrderId\":\"" + order.id() + "\",\"status\":\"" + nextStatus
              + "\",\"refundedMinor\":" + event.amountMinor() + "}")
          .update();
    }
    return find(order.id()).orElseThrow();
  }

  private Optional<PaymentContracts.PaymentOrder> findForUpdate(PaymentContracts.ProviderPaymentEvent event,
                                                                 String provider) {
    if (event.paymentOrderId() != null) {
      return db.sql("select * from payment_order where id=:id for update")
          .param("id", event.paymentOrderId()).query(this::map).optional();
    }
    if (event.providerReference() == null || event.providerReference().isBlank()) return Optional.empty();
    return db.sql("select * from payment_order where provider=:provider and provider_reference=:reference for update")
        .param("provider", provider).param("reference", event.providerReference())
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
        rs.getString("checkout_url"),
        rs.getObject("created_at", OffsetDateTime.class));
  }

  private record HoldRow(UUID customerId, String status) {}
}
