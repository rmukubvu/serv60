package com.serv60.booking.internal.application;

import java.time.Clock;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Releases unpaid holds past their expiry. Each batch is its own short transaction that skips
 * holds a confirmation or payment is currently locking; at most {@code MAX_BATCHES} run per tick
 * so one sweep can never monopolise the scheduler.
 */
@Component
@ConditionalOnProperty(name = "serv60.booking.expiry-sweep-enabled", havingValue = "true", matchIfMissing = true)
public class HoldExpiryJob {
  private static final Logger log = LoggerFactory.getLogger(HoldExpiryJob.class);
  static final int MAX_BATCHES = 10;

  private final BookingService bookings;
  private final Clock clock;
  private final int batchSize;

  public HoldExpiryJob(BookingService bookings, Clock clock,
                       @Value("${serv60.booking.expiry-batch-size:200}") int batchSize) {
    if (batchSize < 1 || batchSize > 1000) {
      throw new IllegalArgumentException("serv60.booking.expiry-batch-size must be between 1 and 1000");
    }
    this.bookings = bookings;
    this.clock = clock;
    this.batchSize = batchSize;
  }

  @Scheduled(fixedDelayString = "${serv60.booking.expiry-sweep-ms:30000}")
  public void expireDueHolds() {
    var now = OffsetDateTime.now(clock);
    for (int batch = 0; batch < MAX_BATCHES; batch++) {
      if (bookings.expireDueHolds(now, batchSize) < batchSize) return;
    }
    log.warn("Hold expiry backlog exceeds {} holds; continuing next tick", MAX_BATCHES * batchSize);
  }
}
