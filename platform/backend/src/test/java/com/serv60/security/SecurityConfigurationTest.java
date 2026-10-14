package com.serv60.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.serv60.booking.internal.adapters.web.BookingController;
import com.serv60.booking.internal.application.BookingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;

@WebMvcTest(controllers = BookingController.class, properties = "serv60.security.enabled=true")
@Import({SecurityConfiguration.class, OperatorAuthorization.class, HubAuthorization.class})
class SecurityConfigurationTest {
  @Autowired MockMvc mvc;
  @MockitoBean BookingService bookings;
  @MockitoBean CustomerAuthorization customerAuthorization;
  @MockitoBean com.serv60.roadside.internal.application.RoadsideService roadside;
  @MockitoBean JwtDecoder jwtDecoder;

  @BeforeEach
  void setUp() {
    when(bookings.findHold(any())).thenReturn(Optional.empty());
  }

  @Test
  void appointmentContactRequiresOperatorAndMatchingHub() throws Exception {
    String hub = "00000000-0000-0000-0000-000000000060";
    String id = "00000000-0000-0000-0000-000000000099";
    String path = "/v1/hubs/" + hub + "/appointments/" + id + "/details";
    mvc.perform(get(path)).andExpect(status().isUnauthorized());
    mvc.perform(get(path).with(jwt().jwt(token -> token.claim("hub_ids", java.util.List.of(hub)))))
        .andExpect(status().isForbidden());
    mvc.perform(get(path).with(jwt().jwt(token -> token.claim("roles", java.util.List.of("HUB_OPERATOR"))
        .claim("hub_ids", java.util.List.of("another-hub")))))
        .andExpect(status().isForbidden());
    org.mockito.Mockito.verify(bookings, org.mockito.Mockito.never()).appointmentDetails(any(), any());
  }

  @Test
  void authorizedOperatorCanReadContactWithoutHttpCaching() throws Exception {
    var hub = java.util.UUID.fromString("00000000-0000-0000-0000-000000000060");
    var id = java.util.UUID.fromString("00000000-0000-0000-0000-000000000099");
    var contact = new com.serv60.booking.api.BookingContracts.AppointmentContact("Alex Demo", "0000000000", "Toyota", "Corolla", "DEMO 060");
    when(bookings.appointmentDetails(hub, id)).thenReturn(Optional.of(new com.serv60.booking.api.BookingContracts.AppointmentDetails(id, contact, null)));
    mvc.perform(get("/v1/hubs/" + hub + "/appointments/" + id + "/details")
        .with(jwt().jwt(token -> token.claim("roles", java.util.List.of("HUB_OPERATOR")).claim("hub_ids", java.util.List.of(hub.toString())))))
        .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.contact.customerName").value("Alex Demo"));
  }

  @Test
  void authorizedOperatorSeesNotFoundForMissingAppointment() throws Exception {
    String hub = "00000000-0000-0000-0000-000000000060";
    mvc.perform(get("/v1/hubs/" + hub + "/appointments/00000000-0000-0000-0000-000000000099/details")
        .with(jwt().jwt(token -> token.claim("roles", java.util.List.of("HUB_OPERATOR")).claim("hub_ids", java.util.List.of(hub)))))
        .andExpect(status().isNotFound());
  }

  @Test
  void enabledSecurityWithoutAJwtIssuerFailsStartupWithGuidance() {
    @SuppressWarnings("unchecked")
    var none = (org.springframework.beans.factory.ObjectProvider<JwtDecoder>)
        org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> SecurityConfiguration.requireJwtDecoder(none))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("issuer-uri");
  }

  @Test
  void securityIsEnabledWhenTheEnvironmentDoesNotSayOtherwise() throws Exception {
    var yaml = new org.springframework.boot.env.YamlPropertySourceLoader()
        .load("application", new org.springframework.core.io.ClassPathResource("application.yml"))
        .getFirst();
    var environment = new org.springframework.core.env.StandardEnvironment();
    environment.getPropertySources().addFirst(yaml);
    environment.getPropertySources().remove("systemEnvironment");

    org.assertj.core.api.Assertions.assertThat(
            environment.getProperty("serv60.security.enabled", Boolean.class))
        .isTrue();
  }

  @Test
  void actuatorDetailsNeedAnAdministratorWhileHealthStaysPublic() throws Exception {
    mvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
    mvc.perform(get("/actuator/metrics").with(jwt().jwt(token -> token.claim("roles", java.util.List.of("HUB_OPERATOR")))))
        .andExpect(status().isForbidden());
    // The web slice has no actuator endpoints, so 404 means the security rule let the admin through.
    mvc.perform(get("/actuator/metrics").with(jwt().jwt(token -> token.claim("roles", java.util.List.of("ADMIN")))))
        .andExpect(status().isNotFound());
    mvc.perform(get("/actuator/health/liveness")).andExpect(status().isNotFound());
  }

  @Test
  void browsersMayNotSendWebhookSignatureHeaders() throws Exception {
    mvc.perform(options("/v1/visit-holds/00000000-0000-0000-0000-000000000001")
            .header("Origin", "http://localhost:3000")
            .header("Access-Control-Request-Method", "POST")
            .header("Access-Control-Request-Headers", "webhook-signature"))
        .andExpect(status().isForbidden());
  }

  @Test
  void protectedApiRejectsAnonymousRequests() throws Exception {
    mvc.perform(get("/v1/visit-holds/00000000-0000-0000-0000-000000000001"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void protectedApiAcceptsAuthenticatedJwt() throws Exception {
    mvc.perform(get("/v1/visit-holds/00000000-0000-0000-0000-000000000001").with(jwt()))
        .andExpect(status().isNotFound());
  }

  @Test
  void operationsWebOriginCanPreflightApiRequests() throws Exception {
    mvc.perform(options("/v1/visit-holds/00000000-0000-0000-0000-000000000001")
            .header("Origin", "http://localhost:3000")
            .header("Access-Control-Request-Method", "GET"))
        .andExpect(status().isOk())
        .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:3000"));
  }

  @Test
  void customersCannotBypassPaymentOrChangePolicyThroughLegacyOperatorCommands() throws Exception {
    var customer=jwt().jwt(token->token.claim("hub_ids",java.util.List.of("00000000-0000-0000-0000-000000000060")));
    for(var action:java.util.List.of("confirm","cancel")) {
      mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/v1/visit-holds/00000000-0000-0000-0000-000000000099/"+action).with(customer))
          .andExpect(status().isForbidden());
    }
    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/v1/visit-holds").with(customer)
        .header("Idempotency-Key","bypass").contentType("application/json").content("""
            {"hubId":"00000000-0000-0000-0000-000000000060","customerId":"00000000-0000-0000-0000-000000000001",
             "serviceCode":"EXPRESS_OIL","startsAt":"2033-01-02T08:00:00Z","endsAt":"2033-01-02T09:00:00Z"}
            """))
        .andExpect(status().isForbidden());
  }
}
