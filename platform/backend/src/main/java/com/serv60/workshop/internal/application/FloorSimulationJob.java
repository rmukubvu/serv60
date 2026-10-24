package com.serv60.workshop.internal.application;

import com.serv60.workshop.api.VisitStage;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Demo only: walks confirmed visits through the floor so the board and the app move without a
 * technician recording stages. Off unless {@code serv60.demo.floor-simulation.enabled=true}. A
 * visit waits in its stage while a proposal is pending with the customer, and stops at READY:
 * handing the car over stays a human action.
 */
@Component
@ConditionalOnProperty(name = "serv60.demo.floor-simulation.enabled", havingValue = "true")
public class FloorSimulationJob {
  private static final Logger log = LoggerFactory.getLogger(FloorSimulationJob.class);
  static final String SIMULATION = "floor-simulation";

  private final WorkshopService workshop;
  private final Clock clock;
  private final Duration stageBase;
  private final boolean autoCheckIn;

  public FloorSimulationJob(
      WorkshopService workshop,
      Clock clock,
      @Value("${serv60.demo.floor-simulation.stage-seconds:60}") long stageSeconds,
      @Value("${serv60.demo.floor-simulation.auto-check-in:true}") boolean autoCheckIn) {
    if (stageSeconds < 1 || stageSeconds > 3600) {
      throw new IllegalArgumentException("serv60.demo.floor-simulation.stage-seconds must be between 1 and 3600");
    }
    this.workshop = workshop;
    this.clock = clock;
    this.stageBase = Duration.ofSeconds(stageSeconds);
    this.autoCheckIn = autoCheckIn;
    log.warn("Floor simulation is ON: visits advance on their own. Never enable this for a real hub");
  }

  @Scheduled(fixedDelayString = "${serv60.demo.floor-simulation.tick-ms:5000}")
  public void tick() {
    var now = clock.instant();
    if (autoCheckIn) {
      for (var visit : workshop.awaitingCheckIn(now)) {
        attempt(() -> workshop.checkIn(visit.hub(), visit.id(), "Arrived (simulated)", SIMULATION));
      }
    }
    for (var visit : workshop.onTheFloor()) {
      if (!due(visit.stage(), visit.since(), now)) continue;
      visit.stage().next().ifPresent(next ->
          attempt(() -> workshop.recordStage(visit.hub(), visit.id(), next, null, SIMULATION)));
    }
  }

  /** Whether a visit has spent its simulated share of time in {@code stage}. */
  boolean due(VisitStage stage, Instant since, Instant now) {
    if (stage == null || !stage.countsTowardTarget()) return false;
    long millis = Math.round(stageBase.toMillis() * stage.simulatedShare());
    return !now.isBefore(since.plusMillis(millis));
  }

  private void attempt(Runnable step) {
    try {
      step.run();
    } catch (RuntimeException failure) {
      // A person may have moved the visit between the read and the write; the next tick re-reads.
      log.debug("Simulation step skipped: {}", failure.getMessage());
    }
  }
}
