package com.serv60.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.serv60.booking.api.BookingContracts;
import com.serv60.booking.internal.application.BookingService;
import com.serv60.payment.api.PaymentContracts;
import com.serv60.payment.internal.application.PaymentService;
import com.serv60.roadside.api.RoadsideContracts;
import com.serv60.roadside.internal.application.RoadsideService;
import com.serv60.support.FixedClock;
import com.serv60.support.SharedPostgres;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * The real application with security on, driven through HTTP with customer and operator tokens.
 * Every other database test runs with security off, so this is where ownership and hub scoping
 * are proven end to end.
 */
@SpringBootTest(
    properties = {
      "serv60.booking.expiry-sweep-enabled=false",
      "serv60.roadside.expiry-sweep-enabled=false",
      "serv60.outbox.prune-enabled=false",
      "serv60.security.enabled=true",
      "serv60.booking.express-price-minor=24900",
      "serv60.booking.hold-duration-seconds=600"
    })
@AutoConfigureMockMvc
@Import(FixedClock.class)
@ContextConfiguration(initializers = SharedPostgres.class)
@Transactional
class SecuredApiIntegrationTest {
  static final UUID HUB = UUID.fromString("00000000-0000-0000-0000-000000000060");
  static final UUID OTHER_HUB = UUID.fromString("00000000-0000-0000-0000-0000000000ff");

  @MockitoBean JwtDecoder decoder;
  @Autowired MockMvc mvc;
  @Autowired BookingService bookings;
  @Autowired PaymentService payments;
  @Autowired RoadsideService roadside;
  @Autowired org.springframework.jdbc.core.simple.JdbcClient db;

  final UUID owner = UUID.randomUUID();

  static JwtRequestPostProcessor customer(UUID id) {
    return jwt().jwt(token -> token.subject(id.toString()));
  }

  static JwtRequestPostProcessor operatorOf(UUID hub, String role) {
    return jwt().jwt(token -> token.subject("staff-" + hub).claim("roles", List.of(role))
        .claim("hub_ids", List.of(hub.toString())));
  }

  BookingContracts.Hold hold() {
    var start = OffsetDateTime.parse("2039-06-01T08:00:00Z");
    return bookings.createHold(new BookingContracts.CreateHold(HUB, owner, null, "PRIVATE_WORK",
        start, start.plusHours(1), "READY_STOCK"), "secured-" + UUID.randomUUID());
  }

  PaymentContracts.PaymentOrder order() {
    return payments.create(new PaymentContracts.CreatePayment(owner, hold().id(), 24900, "ZAR", "YOCO"),
        "secured-pay-" + UUID.randomUUID());
  }

  @Test
  void aCustomerCanReadOnlyTheirOwnHold() throws Exception {
    var hold = hold();

    mvc.perform(get("/v1/visit-holds/{id}", hold.id()).with(customer(owner))).andExpect(status().isOk());
    mvc.perform(get("/v1/visit-holds/{id}", hold.id()).with(customer(UUID.randomUUID())))
        .andExpect(status().isForbidden());
  }

  @Test
  void anOperatorReadsPaymentsOnlyAtTheirOwnHub() throws Exception {
    var order = order();

    mvc.perform(get("/v1/payments/{id}", order.id()).with(operatorOf(HUB, "HUB_OPERATOR")))
        .andExpect(status().isOk());
    mvc.perform(get("/v1/payments/{id}", order.id()).with(operatorOf(OTHER_HUB, "HUB_OPERATOR")))
        .andExpect(status().isForbidden());
  }

  @Test
  void anOperatorOfAnotherHubCannotCreateAPaymentForThisHubsVisit() throws Exception {
    var hold = hold();
    var body = """
        {"customerId":"%s","holdId":"%s","amountMinor":100,"currency":"ZAR","provider":"YOCO"}
        """.formatted(owner, hold.id());

    mvc.perform(post("/v1/payments").with(operatorOf(OTHER_HUB, "HUB_OPERATOR"))
            .header("Idempotency-Key", "cross-hub").contentType("application/json").content(body))
        .andExpect(status().isForbidden());
  }

  @Test
  void aCustomerCannotCreatePaymentsAtAnyPrice() throws Exception {
    var hold = hold();
    var body = """
        {"customerId":"%s","holdId":"%s","amountMinor":1,"currency":"ZAR","provider":"YOCO"}
        """.formatted(owner, hold.id());

    mvc.perform(post("/v1/payments").with(customer(owner))
            .header("Idempotency-Key", "cheap").contentType("application/json").content(body))
        .andExpect(status().isForbidden());
  }

  @Test
  void anIncidentWithNoHubIsOperatedOnlyByAnAdmin() throws Exception {
    var incident = roadside.create(new RoadsideContracts.CreateIncident(owner, null, "N1", "Flat tyre", HUB),
        "secured-incident");
    db.sql("update roadside_incident set hub_id=null where id=:id").param("id", incident.id()).update();

    mvc.perform(get("/v1/roadside/incidents/{id}", incident.id()).with(operatorOf(HUB, "HUB_OPERATOR")))
        .andExpect(status().isForbidden());
    mvc.perform(get("/v1/roadside/incidents/{id}", incident.id()).with(operatorOf(HUB, "ADMIN")))
        .andExpect(status().isOk());
    mvc.perform(get("/v1/roadside/incidents/{id}", incident.id()).with(customer(owner)))
        .andExpect(status().isOk());
  }

  @Test
  void onlyAnOperatorOfTheHubCanRaiseAPhonedInIncident() throws Exception {
    var body = """
        {"customerId":"%s","location":"N1","description":"Flat tyre","hubId":"%s"}
        """.formatted(owner, HUB);

    mvc.perform(post("/v1/roadside/incidents").with(customer(owner))
            .header("Idempotency-Key", "phoned-in").contentType("application/json").content(body))
        .andExpect(status().isForbidden());
    mvc.perform(post("/v1/roadside/incidents").with(operatorOf(OTHER_HUB, "HUB_OPERATOR"))
            .header("Idempotency-Key", "phoned-in").contentType("application/json").content(body))
        .andExpect(status().isForbidden());
    mvc.perform(post("/v1/roadside/incidents").with(operatorOf(HUB, "HUB_OPERATOR"))
            .header("Idempotency-Key", "phoned-in").contentType("application/json").content(body))
        .andExpect(status().isCreated());
  }

  @Test
  void aCustomerCannotCancelOnceTheTruckIsConfirmedButTheHubCan() throws Exception {
    var incident = roadside.create(new RoadsideContracts.CreateIncident(owner, null, "N1", "Flat tyre", HUB),
        "secured-en-route");
    db.sql("update roadside_incident set status='EN_ROUTE' where id=:id").param("id", incident.id()).update();

    mvc.perform(post("/v1/roadside/incidents/{id}/cancel", incident.id()).with(customer(owner)))
        .andExpect(status().isConflict());
    mvc.perform(post("/v1/roadside/incidents/{id}/progress", incident.id())
            .with(operatorOf(OTHER_HUB, "HUB_OPERATOR"))
            .contentType("application/json").content("{\"status\":\"ON_SCENE\"}"))
        .andExpect(status().isForbidden());
    mvc.perform(post("/v1/roadside/incidents/{id}/cancel", incident.id())
            .with(operatorOf(HUB, "HUB_OPERATOR")))
        .andExpect(status().isOk());
  }

  @Test
  void onlyAnAdminSeesUnroutedIncidents() throws Exception {
    mvc.perform(get("/v1/roadside/incidents/dispatch/unrouted").with(operatorOf(HUB, "HUB_OPERATOR")))
        .andExpect(status().isForbidden());
    mvc.perform(get("/v1/roadside/incidents/dispatch/unrouted").with(operatorOf(HUB, "ADMIN")))
        .andExpect(status().isOk());
  }

  @Test
  void anOperatorCannotListACustomersIncidents() throws Exception {
    mvc.perform(get("/v1/roadside/incidents").param("customerId", owner.toString())
            .with(operatorOf(HUB, "HUB_OPERATOR")))
        .andExpect(status().isForbidden());
  }
}
