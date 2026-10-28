package com.serv60.workshop.internal.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.serv60.errors.NotFound;
import com.serv60.workshop.api.WorkshopContracts.FloorVisit;
import com.serv60.workshop.api.WorkshopContracts.VisitProgress;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class FloorStreamsTest {
  /** Records what would be written to the response instead of needing a servlet container. */
  static class RecordingEmitter extends SseEmitter {
    final List<String> frames = new ArrayList<>();
    boolean closed, completed;

    RecordingEmitter() {
      super(60_000L);
    }

    @Override
    public void send(SseEventBuilder builder) throws IOException {
      if (closed) throw new IOException("client went away");
      frames.add(builder.build().stream().map(part -> String.valueOf(part.getData())).collect(Collectors.joining()));
    }

    @Override
    public void complete() {
      completed = true;
    }
  }

  final WorkshopService workshop = mock(WorkshopService.class);
  final UUID hub = UUID.randomUUID();
  final UUID otherHub = UUID.randomUUID();
  final UUID visit = UUID.randomUUID();
  final UUID customer = UUID.randomUUID();
  final OffsetDateTime at = OffsetDateTime.parse("2035-01-01T08:00:00+02:00");
  final List<RecordingEmitter> created = new ArrayList<>();
  final FloorStreams streams = new FloorStreams(workshop, () -> {
    var emitter = new RecordingEmitter();
    created.add(emitter);
    return emitter;
  });

  FloorVisit row(String stage) {
    return new FloorVisit(visit, at, at.plusHours(1), "CONFIRMED", stage, at, null, null, 0, 60,
        "Alex Demo", "Toyota", "DEMO 060", null, "EXPRESS-01", 0, 0, List.of(), null, null);
  }

  VisitProgress progress(String stage) {
    return new VisitProgress(visit, "Hub", at, null, stage, at, null, null, 60, 0, List.of(), List.of(), null);
  }

  @Test
  void shouldSendTheFloorOnConnectAndAfterEachChangeAtThatHub() {
    when(workshop.floor(hub)).thenReturn(List.of(row("CHECKED_IN")), List.of(row("INSPECTING")));

    streams.floor(hub);
    streams.onChange(new FloorChanged(otherHub, UUID.randomUUID()));
    streams.onChange(new FloorChanged(hub, visit));

    var emitter = created.getFirst();
    assertThat(emitter.frames).hasSize(2);
    assertThat(emitter.frames.getFirst()).startsWith("event:floor\n").contains("CHECKED_IN");
    assertThat(emitter.frames.get(1)).contains("INSPECTING");
    assertThat(streams.openStreams()).isEqualTo(1);
    verify(workshop, never()).floor(otherHub);
  }

  @Test
  void shouldStreamProgressOnlyToTheVisitsOwnerSubscribers() {
    when(workshop.progress(customer, visit)).thenReturn(progress("CHECKED_IN"), progress("SERVICING"));

    streams.progress(customer, visit);
    streams.onChange(new FloorChanged(hub, UUID.randomUUID()));
    streams.onChange(new FloorChanged(hub, visit));

    var emitter = created.getFirst();
    assertThat(emitter.frames).hasSize(2);
    assertThat(emitter.frames.getFirst()).startsWith("event:progress\n").contains("CHECKED_IN");
    assertThat(emitter.frames.get(1)).contains("SERVICING");
  }

  @Test
  void shouldRefuseAStreamForAVisitTheCustomerDoesNotOwn() {
    when(workshop.progress(customer, visit)).thenThrow(new NotFound("Appointment not found"));

    assertThatThrownBy(() -> streams.progress(customer, visit)).isInstanceOf(NotFound.class);
    assertThat(streams.openStreams()).isZero();
    assertThat(created).isEmpty();
  }

  @Test
  void shouldForgetAClientThatWentAway() {
    when(workshop.floor(hub)).thenReturn(List.of(row("CHECKED_IN")));
    when(workshop.progress(customer, visit)).thenReturn(progress("CHECKED_IN"));
    streams.floor(hub);
    streams.progress(customer, visit);
    created.forEach(emitter -> emitter.closed = true);

    streams.heartbeat();

    assertThat(streams.openStreams()).isZero();
    assertThat(created).allMatch(emitter -> emitter.completed);
    streams.onChange(new FloorChanged(hub, visit));
    verify(workshop, times(1)).floor(hub);
  }

  @Test
  void shouldKeepIdleStreamsAliveWithComments() {
    when(workshop.floor(hub)).thenReturn(List.of());
    streams.floor(hub);

    streams.heartbeat();

    assertThat(created.getFirst().frames).hasSize(2);
    assertThat(created.getFirst().frames.get(1)).startsWith(":keepalive\n");
  }

  @Test
  void shouldCreateRealEmittersWithTheConfiguredTimeout() {
    when(workshop.floor(hub)).thenReturn(List.of());

    var emitter = new FloorStreams(workshop, 1234L).floor(hub);

    assertThat(emitter.getTimeout()).isEqualTo(1234L);
  }
}
