package com.serv60.booking.internal.application;

import com.serv60.booking.internal.domain.VisitStatus;
import com.serv60.payment.api.PaymentContracts.PaymentSettled;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Confirms a customer's hold when the provider settles its payment, inside the reconciliation
 * transaction. A payment that cannot confirm its hold (released by expiry, or a price mismatch)
 * is never dropped: it is recorded for operator refund or rebooking and counted for alarming.
 */
@Component
class PaymentSettlementHandler {
  private static final Logger log = LoggerFactory.getLogger(PaymentSettlementHandler.class);
  static final String METRIC = "serv60.payment.settlement";

  private final JdbcClient db;
  private final BookingService bookings;
  private final ObjectMapper json;
  private final MeterRegistry metrics;
  private final long priceMinor;

  PaymentSettlementHandler(
      JdbcClient db,
      BookingService bookings,
      ObjectMapper json,
      MeterRegistry metrics,
      @Value("${serv60.booking.express-price-minor:0}") long priceMinor) {
    this.db = db;
    this.bookings = bookings;
    this.json = json;
    this.metrics = metrics;
    this.priceMinor = priceMinor;
  }

  @EventListener
  void on(PaymentSettled payment) {
    var status =
        db.sql("select status from visit_hold where id=:id and customer_id=:customer for update")
            .param("id", payment.holdId())
            .param("customer", payment.customerId())
            .query(String.class)
            .optional()
            .map(VisitStatus::valueOf);
    String outcome = outcome(payment, status);
    metrics.counter(METRIC, "outcome", outcome).increment();
    if (outcome.equals("confirmed")) {
      bookings.confirmSettled(payment.holdId(), "payment:" + payment.paymentOrderId());
    } else if (!outcome.equals("already_confirmed")) {
      needsReconciliation(payment, outcome);
    }
  }

  private String outcome(PaymentSettled payment, java.util.Optional<VisitStatus> hold) {
    if (hold.isEmpty()) return "hold_missing";
    return switch (hold.get()) {
      case CONFIRMED -> "already_confirmed";
      case CANCELLED, EXPIRED -> "hold_" + hold.get().name().toLowerCase(java.util.Locale.ROOT);
      case HELD -> priceMatches(payment) ? "confirmed" : "amount_mismatch";
    };
  }

  private boolean priceMatches(PaymentSettled payment) {
    return priceMinor > 0 && payment.amountMinor() == priceMinor && "ZAR".equals(payment.currency());
  }

  private void needsReconciliation(PaymentSettled payment, String reason) {
    log.error(
        "Settled payment could not confirm its visit paymentOrderId={} holdId={} reason={}",
        payment.paymentOrderId(),
        payment.holdId(),
        reason);
    db.sql(
            "insert into outbox_event(id,event_type,aggregate_id,payload) values"
                + " (:id,'PaidVisitNeedsReconciliation',:aggregate,cast(:payload as jsonb))")
        .param("id", UUID.randomUUID())
        .param("aggregate", payment.holdId())
        .param(
            "payload",
            json.writeValueAsString(
                java.util.Map.of(
                    "paymentOrderId", payment.paymentOrderId(),
                    "holdId", payment.holdId(),
                    "amountMinor", payment.amountMinor(),
                    "reason", reason)))
        .update();
  }
}
