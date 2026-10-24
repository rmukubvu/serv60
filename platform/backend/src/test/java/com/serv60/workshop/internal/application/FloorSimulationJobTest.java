package com.serv60.workshop.internal.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.serv60.errors.Conflict;
import com.serv60.workshop.api.VisitStage;
import com.serv60.workshop.internal.application.WorkshopService.SimulationCandidate;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class FloorSimulationJobTest {
  final WorkshopService workshop = mock(WorkshopService.class);
  final Instant now = Instant.parse("2035-01-01T06:00:00Z");
  final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
  final UUID hub = UUID.randomUUID();
  final FloorSimulationJob job = new FloorSimulationJob(workshop, clock, 60, true);

  @Test
  void shouldHoldAVisitInItsStageForItsShareOfTheBaseTime() {
    // CHECKED_IN takes 0.3 × 60 s = 18 s.
    assertThat(job.due(VisitStage.CHECKED_IN, now.minusSeconds(17), now)).isFalse();
    assertThat(job.due(VisitStage.CHECKED_IN, now.minusSeconds(18), now)).isTrue();
    assertThat(job.due(VisitStage.SERVICING, now.minusSeconds(119), now)).isFalse();
    assertThat(job.due(VisitStage.SERVICING, now.minusSeconds(120), now)).isTrue();
    assertThat(job.due(VisitStage.READY, now.minusSeconds(3600), now)).isFalse();
    assertThat(job.due(null, now.minusSeconds(3600), now)).isFalse();
  }

  @Test
  void shouldCheckInArrivalsAndAdvanceVisitsThatAreDue() {
    var arriving = UUID.randomUUID();
    var servicing = UUID.randomUUID();
    var fresh = UUID.randomUUID();
    when(workshop.awaitingCheckIn(now)).thenReturn(List.of(new SimulationCandidate(hub, arriving, null, now)));
    when(workshop.onTheFloor()).thenReturn(List.of(
        new SimulationCandidate(hub, servicing, VisitStage.SERVICING, now.minusSeconds(130)),
        new SimulationCandidate(hub, fresh, VisitStage.INSPECTING, now.minusSeconds(5))));

    job.tick();

    verify(workshop).checkIn(hub, arriving, "Arrived (simulated)", FloorSimulationJob.SIMULATION);
    verify(workshop).recordStage(hub, servicing, VisitStage.QUALITY_CHECK, null, FloorSimulationJob.SIMULATION);
    verify(workshop, never()).recordStage(eq(hub), eq(fresh), any(), any(), any());
  }

  @Test
  void shouldKeepGoingWhenAPersonMovedTheVisitFirst() {
    var first = UUID.randomUUID();
    var second = UUID.randomUUID();
    when(workshop.awaitingCheckIn(now)).thenReturn(List.of());
    when(workshop.onTheFloor()).thenReturn(List.of(
        new SimulationCandidate(hub, first, VisitStage.WASHING, now.minusSeconds(600)),
        new SimulationCandidate(hub, second, VisitStage.WASHING, now.minusSeconds(600))));
    when(workshop.recordStage(hub, first, VisitStage.READY, null, FloorSimulationJob.SIMULATION))
        .thenThrow(new Conflict("Record READY before HANDED_OVER"));

    job.tick();

    verify(workshop).recordStage(hub, second, VisitStage.READY, null, FloorSimulationJob.SIMULATION);
  }

  @Test
  void shouldLeaveCheckInToPeopleWhenAutoCheckInIsOff() {
    var manual = new FloorSimulationJob(workshop, clock, 60, false);
    when(workshop.onTheFloor()).thenReturn(List.of());

    manual.tick();

    verify(workshop, never()).awaitingCheckIn(any());
    verify(workshop, never()).checkIn(any(), any(), any(), any());
  }

  @Test
  void shouldRejectAnUnusableBaseTime() {
    assertThatThrownBy(() -> new FloorSimulationJob(workshop, clock, 0, true))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new FloorSimulationJob(workshop, clock, 3601, true))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
