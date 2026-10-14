package com.serv60.roadside.internal.adapters.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.serv60.roadside.api.RoadsideContracts;
import com.serv60.roadside.internal.application.RoadsideService;
import com.serv60.security.CustomerAuthorization;
import com.serv60.security.OperatorAuthorization;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

@ExtendWith(MockitoExtension.class)
class RoadsideControllerTest {
  private static final UUID CUSTOMER = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID HUB = UUID.fromString("00000000-0000-0000-0000-000000000060");
  private static final UUID INCIDENT = UUID.fromString("00000000-0000-0000-0000-000000000009");
  @Mock RoadsideService roadside;
  @Mock CustomerAuthorization authorization;
  @Mock OperatorAuthorization operatorAuthorization;
  @Mock com.serv60.roadside.internal.application.RoadsideMarketplace marketplace;
  @Mock com.serv60.security.HubAuthorization hubAuthorization;
  private MockMvc mvc;

  @BeforeEach
  void setUp() {
    mvc =
        MockMvcBuilders.standaloneSetup(
                new RoadsideController(
                    roadside,
                    marketplace,
                    authorization,
                    operatorAuthorization,
                    hubAuthorization))
            .build();
  }

  @Test
  void createsIncidentWithIdempotencyKey() throws Exception {
    var incident = new RoadsideContracts.Incident(INCIDENT, CUSTOMER, null, "OPEN", "Cape Town", "Flat tyre", OffsetDateTime.parse("2026-10-11T08:00:00Z"));
    when(roadside.create(any(), eq("roadside-key"))).thenReturn(incident);
    mvc.perform(post("/v1/roadside/incidents").header("Idempotency-Key", "roadside-key")
        .contentType("application/json").content("{\"customerId\":\"00000000-0000-0000-0000-000000000001\",\"location\":\"Cape Town\",\"description\":\"Flat tyre\"}"))
        .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("OPEN"));
  }

  @Test
  void dispatchBoardRequiresAnOperatorAndListsLiveIncidents() throws Exception {
    var incident = new RoadsideContracts.Incident(INCIDENT, CUSTOMER, null, "SEARCHING", "Cape Town",
        "Flat tyre", OffsetDateTime.parse("2026-10-11T08:00:00Z"));
    when(marketplace.dispatchBoard(HUB, 10)).thenReturn(java.util.List.of(new RoadsideContracts.DispatchBoardItem(
        incident, -33.9, 18.4, null, null, null, null, null, false, HUB, "City Tow", 6, 2.1)));

    mvc.perform(get("/v1/roadside/incidents/dispatch/board").param("hubId", HUB.toString()).param("limit", "10"))
        .andExpect(status().isOk()).andExpect(jsonPath("$[0].incident.status").value("SEARCHING"))
        .andExpect(jsonPath("$[0].nearestPartnerName").value("City Tow"));
    verify(operatorAuthorization).require(any());
    verify(hubAuthorization).require(eq(HUB), any());
  }

  @Test
  void missingIncidentIsNotFound() throws Exception {
    when(roadside.find(INCIDENT)).thenReturn(Optional.empty());
    mvc.perform(get("/v1/roadside/incidents/{id}", INCIDENT)).andExpect(status().isNotFound());
  }

  @Test
  void customerCanCancelAnOpenIncident() throws Exception {
    var incident = new RoadsideContracts.Incident(INCIDENT, CUSTOMER, null, "OPEN", "Cape Town", "Flat tyre", OffsetDateTime.parse("2026-10-11T08:00:00Z"));
    when(roadside.find(INCIDENT)).thenReturn(Optional.of(incident));
    when(roadside.cancel(INCIDENT)).thenReturn(incident);

    mvc.perform(post("/v1/roadside/incidents/{id}/cancel", INCIDENT))
        .andExpect(status().isOk());
    verify(roadside).cancel(INCIDENT);
  }

  @Test
  void customerCanListActiveIncidents() throws Exception {
    var incident = new RoadsideContracts.Incident(INCIDENT, CUSTOMER, null, "OPEN", "Cape Town", "Flat tyre", OffsetDateTime.parse("2026-10-11T08:00:00Z"));
    when(roadside.list(CUSTOMER, 20)).thenReturn(java.util.List.of(incident));

    mvc.perform(get("/v1/roadside/incidents").param("customerId", CUSTOMER.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(INCIDENT.toString()));
  }

  @Test
  void operatorCanReadDispatchQueue() throws Exception {
    var incident = new RoadsideContracts.Incident(INCIDENT, CUSTOMER, null, "OPEN", "Cape Town", "Flat tyre", OffsetDateTime.parse("2026-10-11T08:00:00Z"));
    when(roadside.listDispatchable(HUB, 50)).thenReturn(java.util.List.of(incident));

    mvc.perform(get("/v1/roadside/incidents/dispatch").param("hubId", HUB.toString()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].status").value("OPEN"));
  }
}
