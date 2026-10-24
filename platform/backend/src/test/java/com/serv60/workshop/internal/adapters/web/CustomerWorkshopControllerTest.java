package com.serv60.workshop.internal.adapters.web;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.serv60.errors.ApiExceptionHandler;
import com.serv60.errors.NotFound;
import com.serv60.security.CustomerAuthorization;
import com.serv60.workshop.api.WorkshopContracts.VisitProgress;
import com.serv60.workshop.internal.application.WorkshopService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Progress and decisions act for the authenticated customer only and are never cached. */
class CustomerWorkshopControllerTest {
  final WorkshopService workshop = mock(WorkshopService.class);
  final CustomerAuthorization identity = mock(CustomerAuthorization.class);
  final UUID owner = UUID.randomUUID();
  final UUID visit = UUID.randomUUID();
  final UUID scope = UUID.randomUUID();
  final OffsetDateTime at = OffsetDateTime.parse("2035-01-01T08:00:00+02:00");
  final VisitProgress progress = new VisitProgress(visit, "Serv60 Demo", at, "BARBER", "SERVICING",
      at, null, null, 60, 12, List.of(), List.of(), "COFFEE-ABCD");
  MockMvc mvc;

  @BeforeEach
  void setUp() {
    when(identity.customerId(null)).thenReturn(owner);
    mvc = MockMvcBuilders.standaloneSetup(new CustomerWorkshopController(workshop, identity))
        .setControllerAdvice(new ApiExceptionHandler())
        .build();
  }

  @Test
  void shouldServeTheCustomersOwnProgressWithoutCaching() throws Exception {
    when(workshop.progress(owner, visit)).thenReturn(progress);

    mvc.perform(get("/v1/customer/appointments/{id}/progress", visit))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.stage").value("SERVICING"))
        .andExpect(jsonPath("$.elapsedMinutes").value(12))
        .andExpect(jsonPath("$.voucherCode").value("COFFEE-ABCD"));
  }

  @Test
  void shouldAnswerAProposalForTheAuthenticatedCustomer() throws Exception {
    when(workshop.decide(owner, visit, scope, "APPROVE")).thenReturn(progress);

    mvc.perform(put("/v1/customer/appointments/{id}/scope-changes/{scope}/decision", visit, scope)
            .contentType("application/json").content("{\"decision\":\"APPROVE\"}"))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.visitId").value(visit.toString()));
    verify(workshop).decide(owner, visit, scope, "APPROVE");
  }

  @Test
  void shouldRejectAnythingButApproveOrDecline() throws Exception {
    mvc.perform(put("/v1/customer/appointments/{id}/scope-changes/{scope}/decision", visit, scope)
            .contentType("application/json").content("{\"decision\":\"MAYBE\"}"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(workshop);
  }

  @Test
  void shouldReportAnotherCustomersVisitAsNotFound() throws Exception {
    when(workshop.progress(owner, visit)).thenThrow(new NotFound("Appointment not found"));

    mvc.perform(get("/v1/customer/appointments/{id}/progress", visit))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.error").value("Appointment not found"));
  }
}
