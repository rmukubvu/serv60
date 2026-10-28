package com.serv60.workshop.internal.application;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Live floor and visit progress over server-sent events. A subscriber gets a snapshot on connect
 * and a fresh one after every committed change to that hub or visit, so clients replace state
 * rather than merge deltas. A keepalive comment every few seconds keeps proxies from closing idle
 * streams; the emitter times out after {@code stream-timeout-ms} and clients reconnect.
 */
@Component
public class FloorStreams {
  private static final Logger log = LoggerFactory.getLogger(FloorStreams.class);
  static final String FLOOR = "floor";
  static final String PROGRESS = "progress";

  /** A customer's open progress stream; the customer was authorised when it was opened. */
  record ProgressSubscriber(UUID customer, SseEmitter emitter) {}

  private final WorkshopService workshop;
  private final Supplier<SseEmitter> emitters;
  private final Map<UUID, Set<SseEmitter>> floors = new ConcurrentHashMap<>();
  private final Map<UUID, Set<ProgressSubscriber>> visits = new ConcurrentHashMap<>();

  @Autowired
  public FloorStreams(
      WorkshopService workshop,
      @Value("${serv60.workshop.stream-timeout-ms:1800000}") long timeoutMs) {
    this(workshop, () -> new SseEmitter(timeoutMs));
  }

  FloorStreams(WorkshopService workshop, Supplier<SseEmitter> emitters) {
    this.workshop = workshop;
    this.emitters = emitters;
  }

  /** Opens a stream of a hub's floor; the caller has already been authorised for the hub. */
  public SseEmitter floor(UUID hub) {
    var emitter = emitters.get();
    var subscribers = floors.computeIfAbsent(hub, key -> ConcurrentHashMap.newKeySet());
    subscribers.add(emitter);
    Runnable leave = () -> subscribers.remove(emitter);
    emitter.onCompletion(leave);
    emitter.onTimeout(leave);
    emitter.onError(failure -> leave.run());
    send(emitter, FLOOR, workshop.floor(hub));
    return emitter;
  }

  /** Opens a stream of one visit's progress for its owner; an unowned visit is NotFound here. */
  public SseEmitter progress(UUID customer, UUID visitId) {
    var snapshot = workshop.progress(customer, visitId);
    var emitter = emitters.get();
    var subscriber = new ProgressSubscriber(customer, emitter);
    var subscribers = visits.computeIfAbsent(visitId, key -> ConcurrentHashMap.newKeySet());
    subscribers.add(subscriber);
    Runnable leave = () -> subscribers.remove(subscriber);
    emitter.onCompletion(leave);
    emitter.onTimeout(leave);
    emitter.onError(failure -> leave.run());
    send(emitter, PROGRESS, snapshot);
    return emitter;
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onChange(FloorChanged change) {
    var floorSubscribers = floors.get(change.hubId());
    if (floorSubscribers != null && !floorSubscribers.isEmpty()) {
      var rows = workshop.floor(change.hubId());
      floorSubscribers.forEach(emitter -> send(emitter, FLOOR, rows));
    }
    var visitSubscribers = visits.get(change.visitId());
    if (visitSubscribers != null) {
      for (var subscriber : visitSubscribers) {
        send(subscriber.emitter(), PROGRESS, workshop.progress(subscriber.customer(), change.visitId()));
      }
    }
  }

  @Scheduled(fixedDelayString = "${serv60.workshop.stream-heartbeat-ms:15000}")
  public void heartbeat() {
    floors.values().forEach(set -> set.forEach(this::keepalive));
    visits.values().forEach(set -> set.forEach(subscriber -> keepalive(subscriber.emitter())));
  }

  /** How many streams are open, for tests and diagnostics. */
  int openStreams() {
    return floors.values().stream().mapToInt(Set::size).sum()
        + visits.values().stream().mapToInt(Set::size).sum();
  }

  private void send(SseEmitter emitter, String name, Object data) {
    try {
      emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
    } catch (IOException | IllegalStateException gone) {
      log.debug("Dropping a closed {} stream: {}", name, gone.getMessage());
      forget(emitter);
    }
  }

  private void keepalive(SseEmitter emitter) {
    try {
      emitter.send(SseEmitter.event().comment("keepalive"));
    } catch (IOException | IllegalStateException gone) {
      forget(emitter);
    }
  }

  /** The client went away: drop it from every registry and let the container release it. */
  private void forget(SseEmitter emitter) {
    floors.values().forEach(set -> set.remove(emitter));
    visits.values().forEach(set -> set.removeIf(subscriber -> subscriber.emitter() == emitter));
    emitter.complete();
  }
}
