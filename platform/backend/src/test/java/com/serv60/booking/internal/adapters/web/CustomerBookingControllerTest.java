package com.serv60.booking.internal.adapters.web;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.serv60.booking.internal.application.CustomerBookingService;
import com.serv60.concierge.api.AssistantUnavailable;
import com.serv60.concierge.internal.adapters.web.ConciergeController;
import com.serv60.concierge.internal.application.*;
import com.serv60.errors.ApiExceptionHandler;
import com.serv60.hubs.api.HubDiscovery;
import com.serv60.security.CustomerAuthorization;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Every customer route acts for the authenticated customer only and is never cached. */
class CustomerBookingControllerTest {
  final CustomerBookingService bookings = mock(CustomerBookingService.class);
  final HubDiscovery hubs = mock(HubDiscovery.class);
  final CustomerAuthorization identity = mock(CustomerAuthorization.class);
  final BookingAssistant assistant = mock(BookingAssistant.class);
  final ProposalStore proposals = mock(ProposalStore.class);
  final UUID owner = UUID.randomUUID();
  final UUID hub = UUID.randomUUID();
  final UUID id = UUID.randomUUID();
  MockMvc mvc;

  @BeforeEach
  void setUp() {
    when(identity.customerId(null)).thenReturn(owner);
    mvc =
        MockMvcBuilders.standaloneSetup(
                new CustomerBookingController(bookings, hubs, identity),
                new ConciergeController(assistant, proposals, identity))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();
  }

  static String visit(UUID hub) {
    return """
        {"hubId":"%s","startsAt":"2035-01-03T08:00:00Z","contact":{"customerName":"Demo","phoneNumber":"+27000000000"}}
        """
        .formatted(hub);
  }

  static String chat(String role) {
    return "{\"messages\":[{\"role\":\"" + role + "\",\"content\":\"Hello\"}]}";
  }

  @Test
  void shouldServeHubDiscoveryWithoutHttpCaching() throws Exception {
    mvc.perform(get("/v1/customer/hubs").param("latitude", "-33.9").param("longitude", "18.4"))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"));
  }

  @Test
  void shouldServeHubDetailAndSlotsToASignedInCustomer() throws Exception {
    mvc.perform(get("/v1/customer/hubs/{hub}", hub)).andExpect(status().isOk());

    mvc.perform(get("/v1/customer/hubs/{hub}/slots", hub).param("date", "2035-01-03"))
        .andExpect(status().isOk());

    verify(bookings).slots(owner, hub, java.time.LocalDate.parse("2035-01-03"), null, null);
  }

  @Test
  void shouldListOnlyTheAuthenticatedCustomersAppointments() throws Exception {
    mvc.perform(get("/v1/customer/appointments")).andExpect(status().isOk());

    verify(bookings).upcoming(owner, 20, null, null);
  }

  @Test
  void shouldCreateAnAppointmentForTheAuthenticatedCustomer() throws Exception {
    mvc.perform(post("/v1/customer/appointments").header("Idempotency-Key", "key")
            .contentType("application/json").content(visit(hub)))
        .andExpect(status().isCreated());

    verify(bookings).create(eq(owner), any(), eq("key"));
  }

  @Test
  void shouldRejectAnAppointmentWithoutRequiredFields() throws Exception {
    mvc.perform(post("/v1/customer/appointments").header("Idempotency-Key", "key")
            .contentType("application/json").content("{}"))
        .andExpect(status().isBadRequest());

    verify(bookings, never()).create(any(), any(), any());
  }

  @Test
  void shouldChangeAnAppointmentAtAnExplicitRevision() throws Exception {
    mvc.perform(put("/v1/customer/appointments/{id}", id).header("Idempotency-Key", "key")
            .contentType("application/json").content("{\"startsAt\":\"2035-01-04T08:00:00Z\",\"revision\":0}"))
        .andExpect(status().isOk());

    verify(bookings).change(eq(owner), eq(id), any(), eq("key"));
  }

  @Test
  void shouldRejectAChangeThatOmitsTheRevision() throws Exception {
    mvc.perform(put("/v1/customer/appointments/{id}", id).header("Idempotency-Key", "no-revision")
            .contentType("application/json").content("{\"startsAt\":\"2035-01-04T08:00:00Z\"}"))
        .andExpect(status().isBadRequest());

    verify(bookings, never()).change(any(), any(), any(), any());
  }

  @Test
  void shouldStartPaymentForTheOwner() throws Exception {
    mvc.perform(post("/v1/customer/appointments/{id}/payment", id)).andExpect(status().isOk());

    verify(bookings).payment(owner, id);
  }

  @Test
  void shouldConfirmPaymentForTheOwner() throws Exception {
    mvc.perform(put("/v1/customer/appointments/{id}/confirm-payment", id)).andExpect(status().isOk());

    verify(bookings).confirmPaid(owner, id);
  }

  @Test
  void shouldCancelForTheOwner() throws Exception {
    mvc.perform(post("/v1/customer/appointments/{id}/cancel", id)).andExpect(status().isOk());

    verify(bookings).cancel(owner, id);
  }

  @Test
  void shouldRelayAnAssistantTurnForTheOwner() throws Exception {
    mvc.perform(post("/v1/customer/hubs/{hub}/assistant", hub)
            .contentType("application/json").content(chat("user")))
        .andExpect(status().isOk());

    verify(assistant).reply(eq(owner), eq(hub), any());
  }

  @Test
  void shouldConfirmAnAssistantProposalForTheOwner() throws Exception {
    mvc.perform(put("/v1/customer/assistant/proposals/{id}/confirm", id)).andExpect(status().isOk());

    verify(proposals).confirm(owner, id);
  }

  @Test
  void shouldAnswer503WithRetryAfterWhenTheAssistantIsUnavailable() throws Exception {
    when(assistant.reply(any(), any(), any())).thenThrow(new AssistantUnavailable("Try later"));

    mvc.perform(post("/v1/customer/hubs/{hub}/assistant", hub)
            .contentType("application/json").content(chat("user")))
        .andExpect(status().isServiceUnavailable())
        .andExpect(header().string("Retry-After", "30"))
        .andExpect(jsonPath("$.error").value("Try later"));
  }

  @Test
  void shouldRejectAConversationThatImpersonatesTheSystem() throws Exception {
    mvc.perform(post("/v1/customer/hubs/{hub}/assistant", hub)
            .contentType("application/json").content(chat("system")))
        .andExpect(status().isBadRequest());

    verify(assistant, never()).reply(any(), any(), any());
  }
}
