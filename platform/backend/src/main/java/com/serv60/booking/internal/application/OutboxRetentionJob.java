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
 * Keeps the outbox from growing forever. Retention must outlast every reader of old events: the
 * ops payment alerts look back 30 days, so the minimum is 31.
 */
@Component
@ConditionalOnProperty(name = "serv60.outbox.prune-enabled", havingValue = "true", matchIfMissing = true)
public class OutboxRetentionJob {
  private static final Logger log = LoggerFactory.getLogger(OutboxRetentionJob.class);
  static final int BATCH_SIZE = 1000;
  static final int MAX_BATCHES = 20;

  private final OutboxLeaseService outbox;
  private final Clock clock;
  private final int retentionDays;
  private final boolean publishingEnabled;

  public OutboxRetentionJob(
      OutboxLeaseService outbox,
      Clock clock,
      @Value("${serv60.outbox.retention-days:45}") int retentionDays,
      @Value("${serv60.outbox.sqs.enabled:false}") boolean publishingEnabled) {
    if (retentionDays < 31) {
      throw new IllegalArgumentException("serv60.outbox.retention-days must be at least 31");
    }
    this.outbox = outbox;
    this.clock = clock;
    this.retentionDays = retentionDays;
    this.publishingEnabled = publishingEnabled;
  }

  @Scheduled(fixedDelayString = "${serv60.outbox.prune-ms:3600000}", initialDelayString = "${serv60.outbox.prune-ms:3600000}")
  public void prune() {
    var cutoff = OffsetDateTime.now(clock).minusDays(retentionDays);
    int total = 0;
    for (int batch = 0; batch < MAX_BATCHES; batch++) {
      int deleted = outbox.prune(cutoff, publishingEnabled, BATCH_SIZE);
      total += deleted;
      if (deleted < BATCH_SIZE) break;
    }
    if (total > 0) log.info("Pruned {} outbox events older than {} days", total, retentionDays);
  }
}
