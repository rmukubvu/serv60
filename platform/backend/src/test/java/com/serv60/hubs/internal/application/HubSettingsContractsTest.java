package com.serv60.hubs.internal.application;

import static org.assertj.core.api.Assertions.*;

import com.serv60.hubs.api.HubSettingsContracts.*;
import java.time.LocalTime;
import java.util.*;
import org.junit.jupiter.api.Test;

class HubSettingsContractsTest {
  final LocalTime open = LocalTime.of(8, 0), close = LocalTime.of(17, 0);
  final Capacity capacity = new Capacity(4, 1, 0, 1);
  final List<TradingDay> days =
      java.util.stream.IntStream.rangeClosed(1, 7)
          .mapToObj(d -> new TradingDay(d, false, open, close))
          .toList();

  @Test
  void shouldValidateCapacityBounds() {
    assertThat(new Capacity(100, 50, 50, 50).expressBays()).isEqualTo(100);
    for (int[] values :
        List.of(
            new int[] {-1, 0, 0, 0},
            new int[] {101, 0, 0, 0},
            new int[] {1, -1, 0, 0},
            new int[] {1, 51, 0, 0},
            new int[] {1, 0, -1, 0},
            new int[] {1, 0, 51, 0},
            new int[] {1, 0, 0, -1},
            new int[] {1, 0, 0, 51}))
      assertThatThrownBy(() -> new Capacity(values[0], values[1], values[2], values[3]))
          .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void shouldRequireUniqueCompleteWeekAndDefensiveCopy() {
    var mutable = new ArrayList<>(days.reversed());
    var update = new Update(0, capacity, mutable);
    mutable.clear();
    assertThat(update.tradingHours()).containsExactlyElementsOf(days);
    assertThatThrownBy(() -> new Update(-1, capacity, days))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Update(0, null, days))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Update(0, new Capacity(0, 0, 0, 0), days))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Update(0, capacity, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Update(0, capacity, List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Update(0, capacity, Collections.nCopies(7, days.getFirst())))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Update(0, capacity, Collections.nCopies(7, null)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void shouldValidateFullHourAndLocalMinutePrecision() {
    assertThat(new TradingDay(1, false, open, open.plusHours(1)).closed()).isFalse();
    assertThat(new TradingDay(7, true, open, open).closed()).isTrue();
    for (int day : List.of(0, 8))
      assertThatThrownBy(() -> new TradingDay(day, false, open, close))
          .isInstanceOf(IllegalArgumentException.class);
    for (LocalTime end :
        List.of(
            open,
            open.minusMinutes(1),
            open.plusMinutes(59),
            close.plusSeconds(1),
            close.plusNanos(1)))
      assertThatThrownBy(() -> new TradingDay(1, false, open, end))
          .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new TradingDay(1, false, null, close))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new TradingDay(1, false, open, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new TradingDay(1, false, open.plusSeconds(1), close))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new TradingDay(1, false, open.plusNanos(1), close))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
