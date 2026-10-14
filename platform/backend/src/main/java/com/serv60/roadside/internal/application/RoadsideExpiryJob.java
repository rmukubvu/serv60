package com.serv60.roadside.internal.application;

import java.time.Clock;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Closes roadside requests whose quotes all expired before the customer accepted one. Each batch
 * is its own transaction that skips incidents an accept is currently locking.
 */
@Component
@ConditionalOnProperty(name = "serv60.roadside.expiry-sweep-enabled", havingValue = "true", matchIfMissing = true)
public class RoadsideExpiryJob {
  private static final Logger log = LoggerFactory.getLogger(RoadsideExpiryJob.class);
  static final int BATCH_SIZE = 200;
  static final int MAX_BATCHES = 10;

  private final RoadsideMarketplace marketplace;
  private final Clock clock;

  public RoadsideExpiryJob(RoadsideMarketplace marketplace, Clock clock) {
    this.marketplace = marketplace;
    this.clock = clock;
  }

  /** Runs every minute; quotes live for ten, so a stale request leaves the board within eleven. */
  @Scheduled(fixedDelayString = "${serv60.roadside.expiry-sweep-ms:60000}")
  public void expireStaleQuotes() {
    var now = OffsetDateTime.now(clock);
    for (int batch = 0; batch < MAX_BATCHES; batch++) {
      if (marketplace.expireStaleQuotes(now, BATCH_SIZE) < BATCH_SIZE) return;
    }
    log.warn("Roadside expiry backlog exceeds {} incidents; continuing next tick", MAX_BATCHES * BATCH_SIZE);
  }
}
