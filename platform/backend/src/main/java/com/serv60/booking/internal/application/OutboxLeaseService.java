package com.serv60.booking.internal.application;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboxLeaseService {
  private final JdbcClient db;
  private final Clock clock;

  public OutboxLeaseService(JdbcClient db, Clock clock, MeterRegistry metrics) {
    this.db = db;
    this.clock = clock;
    // Registered here, not in the publisher, so a disabled publisher still shows a growing backlog.
    Gauge.builder("serv60.outbox.pending", this, OutboxLeaseService::pendingCount)
        .description("Outbox events awaiting publication").register(metrics);
    Gauge.builder("serv60.outbox.oldest.pending.age", this, OutboxLeaseService::oldestPendingAgeSeconds)
        .baseUnit("seconds").description("Age of the oldest unpublished outbox event").register(metrics);
    Gauge.builder("serv60.outbox.dead.lettered", this, OutboxLeaseService::deadLetteredCount)
        .description("Outbox events that exhausted their publish attempts").register(metrics);
  }

  @Transactional
  public List<OutboxEvent> claim(int batchSize, Duration leaseDuration) {
    if (batchSize < 1 || batchSize > 500) throw new IllegalArgumentException("batchSize must be between 1 and 500");
    if (leaseDuration.isNegative() || leaseDuration.isZero()) throw new IllegalArgumentException("leaseDuration must be positive");
    var now = OffsetDateTime.now(clock);
    var leaseUntil = now.plus(leaseDuration);
    return db.sql("""
        with candidates as (
          select id from outbox_event
           where published_at is null and dead_lettered_at is null and available_at <= :now
             and (lease_until is null or lease_until < :now)
           order by occurred_at, id
           limit :batchSize
           for update skip locked
        )
        update outbox_event e
           set lease_until=:leaseUntil, attempt_count=e.attempt_count+1
          from candidates c
         where e.id=c.id
        returning e.id, e.event_type, e.aggregate_id, e.payload::text payload, e.attempt_count
        """)
        .param("now", now).param("leaseUntil", leaseUntil).param("batchSize", batchSize)
        .query((rs, row) -> new OutboxEvent(rs.getObject("id", UUID.class), rs.getString("event_type"),
            rs.getObject("aggregate_id", UUID.class), rs.getString("payload"), rs.getInt("attempt_count"), leaseUntil))
        .list();
  }

  @Transactional
  public void markPublished(UUID eventId) {
    db.sql("update outbox_event set published_at=:now, lease_until=null, last_error=null where id=:id and published_at is null")
        .param("now", OffsetDateTime.now(clock)).param("id", eventId).update();
  }

  @Transactional
  public void release(UUID eventId, String error, Duration retryAfter) {
    if (error == null || error.isBlank()) throw new IllegalArgumentException("error is required");
    if (error.length() > 1000) throw new IllegalArgumentException("error is too long");
    if (retryAfter.isNegative() || retryAfter.isZero()) throw new IllegalArgumentException("retryAfter must be positive");
    db.sql("update outbox_event set lease_until=null, available_at=:availableAt, last_error=:error where id=:id and published_at is null")
        .param("availableAt", OffsetDateTime.now(clock).plus(retryAfter)).param("error", error).param("id", eventId).update();
  }

  /** Stops retrying an event that exhausted its attempts; an operator replays or discards it. */
  @Transactional
  public void deadLetter(UUID eventId, String error) {
    db.sql("update outbox_event set dead_lettered_at=:now, lease_until=null, last_error=:error where id=:id and published_at is null")
        .param("now", OffsetDateTime.now(clock)).param("error", error).param("id", eventId).update();
  }

  /**
   * Deletes up to {@code batchSize} events older than {@code cutoff} that were published, or that
   * never will be because publishing is off. Dead letters are always kept for an operator.
   * Returns how many were deleted.
   */
  @Transactional
  public int prune(OffsetDateTime cutoff, boolean publishingEnabled, int batchSize) {
    return db.sql("""
        delete from outbox_event where id in (
          select id from outbox_event where published_at < :cutoff limit :batch)
        """).param("cutoff", cutoff).param("batch", batchSize).update()
        + (publishingEnabled ? 0 : db.sql("""
        delete from outbox_event where id in (
          select id from outbox_event
           where published_at is null and dead_lettered_at is null and available_at < :cutoff
           limit :batch)
        """).param("cutoff", cutoff).param("batch", batchSize).update());
  }

  double pendingCount() {
    return db.sql("select count(*) from outbox_event where published_at is null and dead_lettered_at is null")
        .query(Long.class).single();
  }

  double oldestPendingAgeSeconds() {
    return db.sql("""
        select coalesce(extract(epoch from (cast(:now as timestamptz) - min(occurred_at))), 0)
          from outbox_event where published_at is null and dead_lettered_at is null
        """).param("now", OffsetDateTime.now(clock)).query(Double.class).single();
  }

  double deadLetteredCount() {
    return db.sql("select count(*) from outbox_event where dead_lettered_at is not null")
        .query(Long.class).single();
  }

  public record OutboxEvent(UUID id, String eventType, UUID aggregateId, String payload,
                            int attemptCount, OffsetDateTime leaseUntil) {}
}
