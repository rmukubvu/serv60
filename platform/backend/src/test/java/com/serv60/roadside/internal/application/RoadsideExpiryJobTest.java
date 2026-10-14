package com.serv60.roadside.internal.application;

import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class RoadsideExpiryJobTest {
  final RoadsideMarketplace marketplace = mock(RoadsideMarketplace.class);
  final Instant now = Instant.parse("2035-01-01T06:00:00Z");
  final OffsetDateTime expected = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);
  final RoadsideExpiryJob job = new RoadsideExpiryJob(marketplace, Clock.fixed(now, ZoneOffset.UTC));

  @Test
  void shouldSweepAtTheInjectedClockTimeAndStopOnAPartialBatch() {
    when(marketplace.expireStaleQuotes(expected, RoadsideExpiryJob.BATCH_SIZE))
        .thenReturn(RoadsideExpiryJob.BATCH_SIZE, 3);

    job.expireStaleQuotes();

    verify(marketplace, times(2)).expireStaleQuotes(expected, RoadsideExpiryJob.BATCH_SIZE);
  }

  @Test
  void shouldBoundOneTickWhenTheBacklogIsLarge() {
    when(marketplace.expireStaleQuotes(expected, RoadsideExpiryJob.BATCH_SIZE))
        .thenReturn(RoadsideExpiryJob.BATCH_SIZE);

    job.expireStaleQuotes();

    verify(marketplace, times(RoadsideExpiryJob.MAX_BATCHES))
        .expireStaleQuotes(expected, RoadsideExpiryJob.BATCH_SIZE);
  }
}
