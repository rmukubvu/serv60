package com.serv60.hubs.internal.application;

import static org.assertj.core.api.Assertions.*;

import com.serv60.hubs.api.HubSettingsContracts.*;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

class HubSettingsContractsTest {
  static final LocalTime OPEN = LocalTime.of(8, 0);
  static final LocalTime CLOSE = LocalTime.of(17, 0);
  static final Capacity CAPACITY = new Capacity(4, 1, 0, 1);
  static final List<TradingDay> WEEK =
      IntStream.rangeClosed(1, 7).mapToObj(d -> new TradingDay(d, false, OPEN, CLOSE)).toList();

  @Test
  void shouldAcceptCapacityAtEveryUpperBound() {
    var capacity = new Capacity(100, 50, 50, 50);

    assertThat(capacity).isEqualTo(new Capacity(100, 50, 50, 50));
  }

  @ParameterizedTest(name = "bays={0} barbers={1} massage={2} workspaces={3}")
  @CsvSource({
    "-1,0,0,0", "101,0,0,0", "1,-1,0,0", "1,51,0,0", "1,0,-1,0", "1,0,51,0", "1,0,0,-1", "1,0,0,51"
  })
  void shouldRejectCapacityOutsideItsBounds(int bays, int barbers, int massage, int workspaces) {
    assertThatThrownBy(() -> new Capacity(bays, barbers, massage, workspaces))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void shouldSortTheWeekAndKeepItsOwnCopy() {
    var mutable = new ArrayList<>(WEEK.reversed());
    var update = new Update(0, CAPACITY, mutable);

    mutable.clear();

    assertThat(update.tradingHours()).containsExactlyElementsOf(WEEK);
  }

  static Stream<Arguments> invalidUpdates() {
    return Stream.of(
        Arguments.of("negative revision", (Executable) () -> new Update(-1, CAPACITY, WEEK)),
        Arguments.of("no capacity", (Executable) () -> new Update(0, null, WEEK)),
        Arguments.of("no express bay", (Executable) () -> new Update(0, new Capacity(0, 0, 0, 0), WEEK)),
        Arguments.of("no week", (Executable) () -> new Update(0, CAPACITY, null)),
        Arguments.of("empty week", (Executable) () -> new Update(0, CAPACITY, List.of())),
        Arguments.of("repeated day", (Executable) () -> new Update(0, CAPACITY, Collections.nCopies(7, WEEK.getFirst()))),
        Arguments.of("null day", (Executable) () -> new Update(0, CAPACITY, Collections.nCopies(7, null))));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidUpdates")
  void shouldRejectAnIncompleteOrInvalidUpdate(String reason, Executable update) {
    assertThatThrownBy(update::execute).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void shouldAcceptAOneHourOpenDayAndAClosedDay() {
    var shortDay = new TradingDay(1, false, OPEN, OPEN.plusHours(1));
    var closed = new TradingDay(7, true, OPEN, OPEN);

    assertThat(List.of(shortDay.closed(), closed.closed())).containsExactly(false, true);
  }

  static Stream<Arguments> invalidDays() {
    return Stream.of(
        Arguments.of("day 0", (Executable) () -> new TradingDay(0, false, OPEN, CLOSE)),
        Arguments.of("day 8", (Executable) () -> new TradingDay(8, false, OPEN, CLOSE)),
        Arguments.of("closes at opening", (Executable) () -> new TradingDay(1, false, OPEN, OPEN)),
        Arguments.of("closes before opening", (Executable) () -> new TradingDay(1, false, OPEN, OPEN.minusMinutes(1))),
        Arguments.of("open under an hour", (Executable) () -> new TradingDay(1, false, OPEN, OPEN.plusMinutes(59))),
        Arguments.of("closing has seconds", (Executable) () -> new TradingDay(1, false, OPEN, CLOSE.plusSeconds(1))),
        Arguments.of("closing has nanos", (Executable) () -> new TradingDay(1, false, OPEN, CLOSE.plusNanos(1))),
        Arguments.of("no opening", (Executable) () -> new TradingDay(1, false, null, CLOSE)),
        Arguments.of("no closing", (Executable) () -> new TradingDay(1, false, OPEN, null)),
        Arguments.of("opening has seconds", (Executable) () -> new TradingDay(1, false, OPEN.plusSeconds(1), CLOSE)),
        Arguments.of("opening has nanos", (Executable) () -> new TradingDay(1, false, OPEN.plusNanos(1), CLOSE)));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidDays")
  void shouldRejectAnInvalidTradingDay(String reason, Executable day) {
    assertThatThrownBy(day::execute).isInstanceOf(IllegalArgumentException.class);
  }
}
