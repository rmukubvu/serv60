package com.serv60.roadside.internal.adapters.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.serv60.booking.internal.adapters.web.ApiExceptionHandler;
import com.serv60.roadside.api.RoadsideContracts.*;
import com.serv60.roadside.internal.application.RoadsideMarketplace;
import com.serv60.security.CustomerAuthorization;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CustomerRoadsideControllerTest {
  final UUID owner = UUID.randomUUID();
  final UUID incident = UUID.randomUUID();
  final RoadsideMarketplace marketplace = mock(RoadsideMarketplace.class);
  final CustomerAuthorization identities = mock(CustomerAuthorization.class);
  MockMvc mvc;

  @BeforeEach
  void setUp() {
    when(identities.customerId(any())).thenReturn(owner);
    mvc = MockMvcBuilders.standaloneSetup(new CustomerRoadsideController(marketplace, identities))
        .setControllerAdvice(new ApiExceptionHandler()).build();
  }

  IncidentDetail detail(UUID customer) {
    return new IncidentDetail(incident, customer, "QUOTED", "M1 north", "Flat tyre", -26.2, 28.0,
        "Workshop", false, null, null, null, null, List.of(), null, OffsetDateTime.parse("2035-01-01T06:00:00Z"));
  }

  @Test
  void shouldCreateTheIncidentForTheAuthenticatedCustomerNotTheBodyCustomer() throws Exception {
    when(marketplace.createGeo(any(), eq("geo-key"))).thenReturn(detail(owner));

    mvc.perform(post("/v1/customer/roadside/incidents").header("Idempotency-Key", "geo-key")
            .contentType("application/json")
            .content("""
                {"customerId":"%s","latitude":-26.2,"longitude":28.0,"locationLabel":"M1 north",
                 "description":"Flat tyre","rideHomeRequested":false}
                """.formatted(UUID.randomUUID())))
        .andExpect(status().isCreated())
        .andExpect(header().string("Cache-Control", "no-store"));
    verify(marketplace).createGeo(argThat(request -> request.customerId().equals(owner)), eq("geo-key"));
  }

  @Test
  void shouldHideAnotherCustomersIncident() throws Exception {
    when(marketplace.detail(incident)).thenReturn(detail(UUID.randomUUID()));

    mvc.perform(get("/v1/customer/roadside/incidents/{id}", incident)).andExpect(status().isBadRequest());
  }

  @Test
  void shouldServeOwnedIncidentNearbyTrucksQuotesAndRidesPrivately() throws Exception {
    var quote = UUID.randomUUID();
    when(marketplace.detail(incident)).thenReturn(detail(owner));
    when(marketplace.nearby(-26.2, 28.0)).thenReturn(List.of());
    when(marketplace.acceptQuote(owner, incident, quote)).thenReturn(detail(owner));
    when(marketplace.startRide(eq(owner), eq(incident), any())).thenReturn(detail(owner));

    mvc.perform(get("/v1/customer/roadside/incidents/{id}", incident)).andExpect(status().isOk());
    mvc.perform(get("/v1/customer/roadside/nearby").param("latitude", "-26.2").param("longitude", "28.0"))
        .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
    mvc.perform(post("/v1/customer/roadside/incidents/{id}/accept-quote", incident)
            .contentType("application/json").content("{\"quoteId\":\"%s\"}".formatted(quote)))
        .andExpect(status().isOk());
    mvc.perform(post("/v1/customer/roadside/incidents/{id}/ride", incident)
            .contentType("application/json").content("{\"dropoffLabel\":\"Home\"}"))
        .andExpect(status().isOk());
  }
}
