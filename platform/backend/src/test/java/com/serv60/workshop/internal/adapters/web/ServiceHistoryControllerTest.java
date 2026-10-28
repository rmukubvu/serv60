package com.serv60.workshop.internal.adapters.web;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.serv60.errors.ApiExceptionHandler;
import com.serv60.security.CustomerAuthorization;
import com.serv60.workshop.api.WorkshopContracts.ServiceRecord;
import com.serv60.workshop.internal.application.WorkshopService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ServiceHistoryControllerTest {
  final WorkshopService workshop = mock(WorkshopService.class);
  final CustomerAuthorization identity = mock(CustomerAuthorization.class);
  final UUID owner = UUID.randomUUID();
  final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ServiceHistoryController(workshop, identity))
      .setControllerAdvice(new ApiExceptionHandler()).build();

  @Test
  void shouldListOnlyTheAuthenticatedCustomersHistoryWithoutCaching() throws Exception {
    when(identity.customerId(null)).thenReturn(owner);
    when(workshop.serviceHistory(owner, 20)).thenReturn(List.of(new ServiceRecord(UUID.randomUUID(),
        "S60-20350101-ABCDEF", "Hub", "Toyota Corolla", "DEMO 060", OffsetDateTime.parse("2035-01-01T09:00:00+02:00"),
        24900, 24900)));

    mvc.perform(get("/v1/customer/service-history"))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$[0].invoiceNumber").value("S60-20350101-ABCDEF"));
    mvc.perform(get("/v1/customer/service-history").param("limit", "5")).andExpect(status().isOk());
    verify(workshop).serviceHistory(owner, 5);
  }
}
