package com.serv60.workshop.internal.adapters.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.serv60.errors.ApiExceptionHandler;
import com.serv60.errors.Conflict;
import com.serv60.security.HubAuthorization;
import com.serv60.security.OperatorAuthorization;
import com.serv60.workshop.api.VisitStage;
import com.serv60.workshop.api.WorkshopContracts.*;
import com.serv60.workshop.internal.application.FloorStreams;
import com.serv60.workshop.internal.application.WorkshopService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class WorkshopControllerTest {
  final WorkshopService workshop = mock(WorkshopService.class);
  final FloorStreams streams = mock(FloorStreams.class);
  final HubAuthorization hubs = mock(HubAuthorization.class);
  final OperatorAuthorization operators = mock(OperatorAuthorization.class);
  final MockMvc mvc =
      MockMvcBuilders.standaloneSetup(new WorkshopController(workshop, streams, hubs, operators))
          .setControllerAdvice(new ApiExceptionHandler())
          .build();
  final UUID hub = UUID.randomUUID();
  final UUID visit = UUID.randomUUID();
  final OffsetDateTime at = OffsetDateTime.parse("2035-01-01T08:00:00+02:00");

  FloorVisit row(String stage) {
    return new FloorVisit(visit, at, at.plusHours(1), "CONFIRMED", stage, at, null, null, 0, 60,
        "Alex Demo", "Toyota Corolla", "DEMO 060", "BARBER", "EXPRESS-01", 0, 0, List.of("INSPECTING"));
  }

  @Test
  void shouldServeTheFloorToAnOperatorOfTheHub() throws Exception {
    when(workshop.floor(hub)).thenReturn(List.of(row("CHECKED_IN")));

    mvc.perform(get("/v1/hubs/{hub}/floor", hub))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].stage").value("CHECKED_IN"))
        .andExpect(jsonPath("$[0].bayCode").value("EXPRESS-01"));
    verify(operators).require(any());
    verify(hubs).require(eq(hub), any());
  }

  @Test
  void shouldOpenTheFloorStreamForAnOperatorOfTheHub() throws Exception {
    when(streams.floor(hub)).thenReturn(new SseEmitter());

    mvc.perform(get("/v1/hubs/{hub}/floor/stream", hub).accept("text/event-stream"))
        .andExpect(request().asyncStarted())
        .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("text/event-stream")));
    verify(operators).require(any());
    verify(hubs).require(eq(hub), any());
    verify(streams).floor(hub);
  }

  @Test
  void shouldRefuseAnOperatorFromAnotherHub() throws Exception {
    doThrow(new AccessDeniedException("not a member")).when(hubs).require(eq(hub), any());

    mvc.perform(get("/v1/hubs/{hub}/floor", hub)).andExpect(status().isForbidden());
    mvc.perform(post("/v1/hubs/{hub}/visits/{id}/check-in", hub, visit)).andExpect(status().isForbidden());
    mvc.perform(get("/v1/hubs/{hub}/floor/stream", hub)).andExpect(status().isForbidden());
    verifyNoInteractions(workshop, streams);
  }

  @Test
  void shouldCheckInWithOrWithoutANote() throws Exception {
    when(workshop.checkIn(eq(hub), eq(visit), any(), eq(WorkshopController.DEMO_OPERATOR)))
        .thenReturn(row("CHECKED_IN"));

    mvc.perform(post("/v1/hubs/{hub}/visits/{id}/check-in", hub, visit))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.nextStages[0]").value("INSPECTING"));
    mvc.perform(post("/v1/hubs/{hub}/visits/{id}/check-in", hub, visit)
            .contentType("application/json").content("{\"note\":\"Keys in box 4\"}"))
        .andExpect(status().isOk());

    verify(workshop).checkIn(hub, visit, null, WorkshopController.DEMO_OPERATOR);
    verify(workshop).checkIn(hub, visit, "Keys in box 4", WorkshopController.DEMO_OPERATOR);
  }

  @Test
  void shouldRecordAKnownStageAndRejectAnUnknownOne() throws Exception {
    when(workshop.recordStage(hub, visit, VisitStage.INSPECTING, null, WorkshopController.DEMO_OPERATOR))
        .thenReturn(row("INSPECTING"));

    mvc.perform(post("/v1/hubs/{hub}/visits/{id}/stages", hub, visit)
            .contentType("application/json").content("{\"stage\":\"INSPECTING\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.stage").value("INSPECTING"));
    mvc.perform(post("/v1/hubs/{hub}/visits/{id}/stages", hub, visit)
            .contentType("application/json").content("{\"stage\":\"FLYING\"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(post("/v1/hubs/{hub}/visits/{id}/stages", hub, visit)
            .contentType("application/json").content("{}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void shouldReportAnOutOfOrderStageAsAConflict() throws Exception {
    when(workshop.recordStage(hub, visit, VisitStage.READY, null, WorkshopController.DEMO_OPERATOR))
        .thenThrow(new Conflict("Record INSPECTING before READY"));

    mvc.perform(post("/v1/hubs/{hub}/visits/{id}/stages", hub, visit)
            .contentType("application/json").content("{\"stage\":\"READY\"}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error").value("Record INSPECTING before READY"));
  }

  @Test
  void shouldProposeExtraWorkWithAtLeastOneItem() throws Exception {
    var proposal = new ScopeChange(UUID.randomUUID(), visit, "PROPOSED",
        List.of(new ScopeItem("Front brake pads", 145000)), "Pads at 2 mm", 145000, "ZAR", at, null);
    when(workshop.propose(eq(hub), eq(visit), any(), eq(WorkshopController.DEMO_OPERATOR))).thenReturn(proposal);

    mvc.perform(post("/v1/hubs/{hub}/visits/{id}/scope-changes", hub, visit)
            .contentType("application/json")
            .content("{\"items\":[{\"description\":\"Front brake pads\",\"amountMinor\":145000}],\"note\":\"Pads at 2 mm\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.totalMinor").value(145000))
        .andExpect(jsonPath("$.status").value("PROPOSED"));
    mvc.perform(post("/v1/hubs/{hub}/visits/{id}/scope-changes", hub, visit)
            .contentType("application/json").content("{\"items\":[]}"))
        .andExpect(status().isBadRequest());
    mvc.perform(post("/v1/hubs/{hub}/visits/{id}/scope-changes", hub, visit)
            .contentType("application/json")
            .content("{\"items\":[{\"description\":\"\",\"amountMinor\":-1}]}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void shouldNameTheSignedInOperatorOrTheDemoFallback() {
    var auth = mock(Authentication.class);
    when(auth.getName()).thenReturn("staff@serv60.test");

    assertThat(WorkshopController.recordedBy(auth)).isEqualTo("staff@serv60.test");
    assertThat(WorkshopController.recordedBy(null)).isEqualTo(WorkshopController.DEMO_OPERATOR);
    when(auth.getName()).thenReturn(" ");
    assertThat(WorkshopController.recordedBy(auth)).isEqualTo(WorkshopController.DEMO_OPERATOR);
  }
}
