package com.serv60.booking.internal.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class HoldExpiryJobTest {
  final BookingService bookings = mock(BookingService.class);
  final Instant now = Instant.parse("2035-01-01T06:00:00Z");
  final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
  final OffsetDateTime expected = OffsetDateTime.ofInstant(now, ZoneOffset.UTC);

  @Test
  void shouldSweepAtTheInjectedClockTimeAndStopOnAPartialBatch() {
    when(bookings.expireDueHolds(expected, 50)).thenReturn(50, 7);

    new HoldExpiryJob(bookings, clock, 50).expireDueHolds();

    verify(bookings, times(2)).expireDueHolds(eq(expected), eq(50));
  }

  @Test
  void shouldBoundOneTickToTenBatchesWhenTheBacklogIsLarge() {
    when(bookings.expireDueHolds(expected, 50)).thenReturn(50);

    new HoldExpiryJob(bookings, clock, 50).expireDueHolds();

    verify(bookings, times(HoldExpiryJob.MAX_BATCHES)).expireDueHolds(expected, 50);
  }

  @Test
  void shouldRejectAnUnboundedBatchSize() {
    assertThatThrownBy(() -> new HoldExpiryJob(bookings, clock, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
