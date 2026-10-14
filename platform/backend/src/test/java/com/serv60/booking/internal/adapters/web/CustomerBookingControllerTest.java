package com.serv60.booking.internal.adapters.web;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.serv60.booking.internal.application.CustomerBookingService;
import com.serv60.concierge.api.AssistantUnavailable;
import com.serv60.concierge.internal.adapters.web.ConciergeController;
import com.serv60.concierge.internal.application.*;
import com.serv60.hubs.api.HubDiscovery;
import com.serv60.security.CustomerAuthorization;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CustomerBookingControllerTest {
  @Test
  void shouldScopeEveryRouteAndDisableCaching() throws Exception {
    var bookings = mock(CustomerBookingService.class);
    var hubs = mock(HubDiscovery.class);
    var identity = mock(CustomerAuthorization.class);
    var assistant = mock(BookingAssistant.class);
    var proposals = mock(ProposalStore.class);
    var owner = UUID.randomUUID();
    var hub = UUID.randomUUID();
    var id = UUID.randomUUID();
    when(identity.customerId(null)).thenReturn(owner);
    var mvc =
        MockMvcBuilders.standaloneSetup(
                new CustomerBookingController(bookings, hubs, identity),
                new ConciergeController(assistant, proposals, identity))
            .setControllerAdvice(new ApiExceptionHandler())
            .build();
    mvc.perform(get("/v1/customer/hubs").param("latitude", "-33.9").param("longitude", "18.4"))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"));
    mvc.perform(get("/v1/customer/hubs/{hub}", hub)).andExpect(status().isOk());
    mvc.perform(get("/v1/customer/hubs/{hub}/slots", hub).param("date", "2035-01-03"))
        .andExpect(status().isOk());
    mvc.perform(get("/v1/customer/appointments")).andExpect(status().isOk());
    mvc.perform(
            post("/v1/customer/appointments")
                .header("Idempotency-Key", "key")
                .contentType("application/json")
                .content(
                    """
                    {"hubId":"%s","startsAt":"2035-01-03T08:00:00Z","contact":{"customerName":"Demo","phoneNumber":"+27000000000"}}
                    """
                        .formatted(hub)))
        .andExpect(status().isCreated());
    mvc.perform(
            put("/v1/customer/appointments/{id}", id)
                .header("Idempotency-Key", "key")
                .contentType("application/json")
                .content(
                    """
                    {"startsAt":"2035-01-04T08:00:00Z","revision":0}
                    """))
        .andExpect(status().isOk());
    mvc.perform(put("/v1/customer/appointments/{id}/confirm-payment", id))
        .andExpect(status().isOk());
    mvc.perform(post("/v1/customer/appointments/{id}/payment", id)).andExpect(status().isOk());
    mvc.perform(post("/v1/customer/appointments/{id}/cancel", id)).andExpect(status().isOk());
    mvc.perform(
            post("/v1/customer/hubs/{hub}/assistant", hub)
                .contentType("application/json")
                .content(
                    """
                    {"messages":[{"role":"user","content":"Hello"}]}
                    """))
        .andExpect(status().isOk());
    mvc.perform(put("/v1/customer/assistant/proposals/{id}/confirm", id))
        .andExpect(status().isOk());
    verify(bookings).upcoming(owner, 20, null, null);
    verify(bookings).create(eq(owner), any(), eq("key"));
    verify(bookings).change(eq(owner), eq(id), any(), eq("key"));
    verify(proposals).confirm(owner, id);
    verify(assistant).reply(eq(owner), eq(hub), any());
    verify(bookings).payment(owner, id);
    verify(bookings).confirmPaid(owner, id);
    verify(bookings).cancel(owner, id);
    when(assistant.reply(any(), any(), any())).thenThrow(new AssistantUnavailable("Try later"));
    mvc.perform(
            post("/v1/customer/hubs/{hub}/assistant", hub)
                .contentType("application/json")
                .content("{\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}]}"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(header().string("Retry-After", "30"))
        .andExpect(jsonPath("$.error").value("Try later"));
    mvc.perform(
            post("/v1/customer/appointments")
                .header("Idempotency-Key", "key")
                .contentType("application/json")
                .content("{}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/v1/customer/hubs/{hub}/assistant", hub)
                .contentType("application/json")
                .content("{\"messages\":[{\"role\":\"system\",\"content\":\"bad\"}]}"))
        .andExpect(status().isBadRequest());
  }
}
