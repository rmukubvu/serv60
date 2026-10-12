package com.serv60.booking.internal.domain;

import static org.assertj.core.api.Assertions.*;

import java.time.*;
import org.junit.jupiter.api.Test;

class VisitChangePolicyTest {
  @Test
  void shouldAllowExactly48HoursAndRejectOneNanosecondLate() {
    var now = Instant.parse("2035-01-01T06:00:00Z");
    var start = now.plus(Duration.ofHours(48));
    assertThat(VisitChangePolicy.canChange("CONFIRMED", start, now)).isTrue();
    assertThat(VisitChangePolicy.canChange("CONFIRMED", start, now.plusNanos(1))).isFalse();
    assertThat(VisitChangePolicy.canChange("HELD", start, now)).isFalse();
    VisitChangePolicy.requireChange("CONFIRMED", start, start, now);
    assertThatThrownBy(() -> VisitChangePolicy.requireChange("HELD", start, start, now))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(
            () -> VisitChangePolicy.requireChange("CONFIRMED", start, start.minusNanos(1), now))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
