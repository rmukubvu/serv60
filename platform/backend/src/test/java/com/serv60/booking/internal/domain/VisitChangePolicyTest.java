package com.serv60.booking.internal.domain;

import static org.assertj.core.api.Assertions.*;

import java.time.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class VisitChangePolicyTest {
  @Test
  void shouldAllowExactly48HoursAndRejectOneNanosecondLate() {
    var now = Instant.parse("2035-01-01T06:00:00Z");
    var start = now.plus(Duration.ofHours(48));
    assertThat(VisitChangePolicy.canChange(VisitStatus.CONFIRMED, start, now)).isTrue();
    assertThat(VisitChangePolicy.canChange(VisitStatus.CONFIRMED, start, now.plusNanos(1))).isFalse();
    assertThat(VisitChangePolicy.canChange(VisitStatus.HELD, start, now)).isFalse();
    VisitChangePolicy.requireChange(VisitStatus.CONFIRMED, start, start, now);
    assertThatThrownBy(() -> VisitChangePolicy.requireChange(VisitStatus.HELD, start, start, now))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () -> VisitChangePolicy.requireChange(VisitStatus.CONFIRMED, start, start.minusNanos(1), now))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @CsvSource({
    "HELD, false, true", "CONFIRMED, true, true", "CANCELLED, false, false", "EXPIRED, false, false"
  })
  void shouldAllowChangesAndCancellationsOnlyFromTheRightStatus(
      VisitStatus status, boolean changeable, boolean cancellable) {
    var now = Instant.parse("2035-01-01T06:00:00Z");
    var start = now.plus(Duration.ofDays(7));

    assertThat(VisitChangePolicy.canChange(status, start, now)).isEqualTo(changeable);
    assertThat(VisitChangePolicy.canCancel(status, start, now)).isEqualTo(cancellable);
    assertThat(status.closed()).isEqualTo(!cancellable);
  }
}
