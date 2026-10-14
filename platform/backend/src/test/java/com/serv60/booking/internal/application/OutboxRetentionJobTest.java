package com.serv60.booking.internal.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class OutboxRetentionJobTest {
  final OutboxLeaseService outbox = mock(OutboxLeaseService.class);
  final Instant now = Instant.parse("2035-01-01T06:00:00Z");
  final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
  final OffsetDateTime cutoff = OffsetDateTime.ofInstant(now, ZoneOffset.UTC).minusDays(45);

  @Test
  void shouldPruneInBatchesUntilAPartialBatch() {
    when(outbox.prune(cutoff, true, OutboxRetentionJob.BATCH_SIZE)).thenReturn(OutboxRetentionJob.BATCH_SIZE, 4);

    new OutboxRetentionJob(outbox, clock, 45, true).prune();

    verify(outbox, times(2)).prune(cutoff, true, OutboxRetentionJob.BATCH_SIZE);
  }

  @Test
  void shouldStopAfterTheBatchLimit() {
    when(outbox.prune(cutoff, false, OutboxRetentionJob.BATCH_SIZE)).thenReturn(OutboxRetentionJob.BATCH_SIZE);

    new OutboxRetentionJob(outbox, clock, 45, false).prune();

    verify(outbox, times(OutboxRetentionJob.MAX_BATCHES)).prune(cutoff, false, OutboxRetentionJob.BATCH_SIZE);
  }

  @Test
  void shouldRefuseARetentionShorterThanThePaymentAlertWindow() {
    assertThatThrownBy(() -> new OutboxRetentionJob(outbox, clock, 30, true))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
