package com.serv60.workshop.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class VisitStageTest {
  @Test
  void shouldOrderTheFloorFromCheckInToHandover() {
    assertThat(VisitStage.after(null)).contains(VisitStage.CHECKED_IN);
    assertThat(VisitStage.CHECKED_IN.next()).contains(VisitStage.INSPECTING);
    assertThat(VisitStage.WASHING.next()).contains(VisitStage.READY);
    assertThat(VisitStage.READY.next()).contains(VisitStage.HANDED_OVER);
    assertThat(VisitStage.HANDED_OVER.next()).isEqualTo(Optional.empty());
  }

  @Test
  void shouldCountOnlyTheWorkBeforeReadyTowardTheTarget() {
    assertThat(VisitStage.WASHING.countsTowardTarget()).isTrue();
    assertThat(VisitStage.READY.countsTowardTarget()).isFalse();
    assertThat(VisitStage.HANDED_OVER.countsTowardTarget()).isFalse();
  }

  @Test
  void shouldGiveServicingTheLargestSimulatedShareAndNoneAfterReady() {
    assertThat(VisitStage.SERVICING.simulatedShare()).isGreaterThan(VisitStage.WASHING.simulatedShare());
    assertThat(VisitStage.READY.simulatedShare()).isZero();
    assertThat(VisitStage.sqlOrder()).startsWith("array['CHECKED_IN',").endsWith("'HANDED_OVER']");
  }
}
