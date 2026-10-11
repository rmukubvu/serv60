package com.serv60.booking.internal.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class HoldExpiryJobTest {
  @Mock BookingService bookings;

  @Test
  void scheduledSweepDelegatesWithCurrentUtcTime() {
    new HoldExpiryJob(bookings).expireDueHolds();

    var captured = ArgumentCaptor.forClass(OffsetDateTime.class);
    verify(bookings).expireDueHolds(captured.capture());
    assertThat(captured.getValue().getOffset()).isEqualTo(ZoneOffset.UTC);
    assertThat(captured.getValue()).isBetween(OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(2),
        OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(2));
  }
}
