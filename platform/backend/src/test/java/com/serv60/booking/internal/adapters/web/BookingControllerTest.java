package com.serv60.booking.internal.adapters.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.serv60.booking.api.BookingContracts;
import com.serv60.booking.internal.application.BookingService;
import com.serv60.security.HubAuthorization;
import com.serv60.security.CustomerAuthorization;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.security.access.AccessDeniedException;

@ExtendWith(MockitoExtension.class)
class BookingControllerTest {
  private static final UUID HUB = UUID.fromString("00000000-0000-0000-0000-000000000060");
  private static final UUID HOLD = UUID.fromString("00000000-0000-0000-0000-000000000099");

  @Mock BookingService bookings;
  @Mock HubAuthorization authorization;
  @Mock CustomerAuthorization customerAuthorization;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    mvc = MockMvcBuilders.standaloneSetup(new BookingController(bookings, authorization, customerAuthorization))
        .setControllerAdvice(new ApiExceptionHandler()).build();
  }

  @Test
  void availabilityIncludesComplimentaryChoice() throws Exception {
    var from = OffsetDateTime.parse("2032-01-02T08:00:00Z");
    var to = from.plusHours(1);
    when(bookings.availability(HUB, "EXPRESS_OIL", from, to, "BARBER")).thenReturn(List.of(
        new BookingContracts.Availability(HUB, "EXPRESS_OIL", from, to, "READY_STOCK")));
    mvc.perform(get("/v1/hubs/{hubId}/availability", HUB).param("serviceCode", "EXPRESS_OIL")
        .param("complimentaryServiceCode", "BARBER").param("from", from.toString()).param("to", to.toString()))
        .andExpect(status().isOk()).andExpect(jsonPath("$[0].serviceCode").value("EXPRESS_OIL"));
  }

  @Test
  void availabilityIsReturnedAsJson() throws Exception {
    var from = OffsetDateTime.of(2026, 10, 11, 8, 0, 0, 0, ZoneOffset.UTC);
    var to = from.plusHours(1);
    when(bookings.availability(HUB, "BARBER", from, to)).thenReturn(List.of(
        new BookingContracts.Availability(HUB, "BARBER", from, to, "READY_STOCK")));

    mvc.perform(get("/v1/hubs/{hubId}/availability", HUB)
            .param("serviceCode", "BARBER").param("from", from.toString()).param("to", to.toString()))
        .andExpect(status().isOk()).andExpect(jsonPath("$[0].serviceCode").value("BARBER"));
  }

  @Test
  void missingVisitHoldIsNotFound() throws Exception {
    when(bookings.findHold(HOLD)).thenReturn(Optional.empty());

    mvc.perform(get("/v1/visit-holds/{id}", HOLD)).andExpect(status().isNotFound());
  }

  @Test
  void partsReadinessHonorsLimit() throws Exception {
    when(bookings.partsReadiness(HUB, 2)).thenReturn(List.of(
        new BookingContracts.PartsReadiness("OIL-5W30", 10, 2, 3)));

    mvc.perform(get("/v1/hubs/{hubId}/parts-readiness", HUB).param("limit", "2"))
        .andExpect(status().isOk()).andExpect(jsonPath("$[0].sku").value("OIL-5W30"));
  }

  @Test
  void calendarReturnsAllocatedResources() throws Exception {
    var from = OffsetDateTime.of(2026, 10, 11, 8, 0, 0, 0, ZoneOffset.UTC);
    when(bookings.calendar(HUB, from, from.plusHours(2))).thenReturn(List.of(
        new BookingContracts.CalendarEntry(HOLD, HOLD, UUID.randomUUID(), "EXPRESS-01",
            "EXPRESS_OIL", from, from.plusHours(1), "CONFIRMED")));

    mvc.perform(get("/v1/hubs/{hubId}/calendar", HUB)
            .param("from", from.toString()).param("to", from.plusHours(2).toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].resourceCode").value("EXPRESS-01"))
        .andExpect(jsonPath("$[0].status").value("CONFIRMED"));
  }

  @Test
  void holdRequestIsCreatedWithIdempotencyKey() throws Exception {
    var hold = new BookingContracts.Hold(HOLD, HUB, UUID.randomUUID(), "BARBER",
        OffsetDateTime.parse("2026-10-11T08:00:00Z"), OffsetDateTime.parse("2026-10-11T08:45:00Z"),
        "HELD", "READY_STOCK", OffsetDateTime.parse("2026-10-11T07:55:00Z"));
    when(bookings.createHold(any(), eq("mobile-key"))).thenReturn(hold);

    mvc.perform(post("/v1/visit-holds").header("Idempotency-Key", "mobile-key")
            .contentType("application/json").content("""
                {"hubId":"00000000-0000-0000-0000-000000000060","customerId":"00000000-0000-0000-0000-000000000001","serviceCode":"BARBER","startsAt":"2026-10-11T08:00:00Z","endsAt":"2026-10-11T08:45:00Z","policy":"READY_STOCK"}
                """))
        .andExpect(status().isCreated()).andExpect(jsonPath("$.id").value(HOLD.toString()));
  }

  @Test
  void cancellationReturnsUpdatedHold() throws Exception {
    var hold = new BookingContracts.Hold(HOLD, HUB, UUID.randomUUID(), "BARBER",
        OffsetDateTime.now(ZoneOffset.UTC), OffsetDateTime.now(ZoneOffset.UTC).plusHours(1),
        "CANCELLED", "READY_STOCK", OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5));
    when(bookings.cancel(HOLD)).thenReturn(hold);
    when(bookings.findHold(HOLD)).thenReturn(Optional.of(hold));

    mvc.perform(post("/v1/visit-holds/{id}/cancel", HOLD))
        .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
  }

  @Test
  void domainConflictIsMappedToConflictResponse() throws Exception {
    when(bookings.confirm(any())).thenThrow(new IllegalStateException("Hold expired"));
    var hold = new BookingContracts.Hold(HOLD, HUB, UUID.randomUUID(), "BARBER",
        OffsetDateTime.now(ZoneOffset.UTC), OffsetDateTime.now(ZoneOffset.UTC).plusHours(1),
        "HELD", "READY_STOCK", OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5));
    when(bookings.findHold(HOLD)).thenReturn(Optional.of(hold));

    mvc.perform(post("/v1/visit-holds/{id}/confirm", HOLD))
        .andExpect(status().isConflict()).andExpect(jsonPath("$.error").value("Hold expired"));
  }

  @Test
  void invalidRequestIsMappedToBadRequestResponse() throws Exception {
    when(bookings.partsReadiness(HUB, 0)).thenThrow(new IllegalArgumentException("limit must be between 1 and 200"));

    mvc.perform(get("/v1/hubs/{hubId}/parts-readiness", HUB).param("limit", "0"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error").value("limit must be between 1 and 200"));
  }

  @Test
  void forbiddenRequestIsMappedToForbiddenResponse() throws Exception {
    doThrow(new AccessDeniedException("hub access denied")).when(authorization).require(eq(HUB), any());

    mvc.perform(get("/v1/hubs/{hubId}/parts-readiness", HUB))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.error").value("hub access denied"));
  }
}
