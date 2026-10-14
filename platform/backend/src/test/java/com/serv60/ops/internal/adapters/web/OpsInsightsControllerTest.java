package com.serv60.ops.internal.adapters.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.serv60.booking.internal.adapters.web.ApiExceptionHandler;
import com.serv60.ops.api.OpsInsightsContracts.OpsInsights;
import com.serv60.ops.internal.application.OpsInsightsService;
import com.serv60.security.HubAuthorization;
import com.serv60.security.OperatorAuthorization;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class OpsInsightsControllerTest {
  final OpsInsightsService insights = mock(OpsInsightsService.class);
  final HubAuthorization hubs = mock(HubAuthorization.class);
  final OperatorAuthorization operators = mock(OperatorAuthorization.class);
  final org.springframework.test.web.servlet.MockMvc mvc =
      MockMvcBuilders.standaloneSetup(new OpsInsightsController(insights, hubs, operators))
          .setControllerAdvice(new ApiExceptionHandler()).build();
  final UUID hub = UUID.randomUUID();

  @Test
  void shouldServeInsightsToAnOperatorOfTheHub() throws Exception {
    when(insights.forHub(hub)).thenReturn(new OpsInsights(List.of(), List.of()));

    mvc.perform(get("/v1/hubs/{hub}/ops-insights", hub))
        .andExpect(status().isOk()).andExpect(jsonPath("$.alerts").isEmpty());
    verify(operators).require(any());
    verify(hubs).require(eq(hub), any());
  }

  @Test
  void shouldRefuseAnOperatorFromAnotherHub() throws Exception {
    doThrow(new AccessDeniedException("not a member")).when(hubs).require(eq(hub), any());

    mvc.perform(get("/v1/hubs/{hub}/ops-insights", hub)).andExpect(status().isForbidden());
    verify(insights, never()).forHub(any());
  }
}
