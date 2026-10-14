package com.serv60.hubs.internal.adapters.web;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.serv60.hubs.api.HubSettingsContracts.*;
import com.serv60.hubs.internal.application.HubSettingsService;
import com.serv60.security.*;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.*;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;
import org.springframework.boot.test.context.TestConfiguration;

@WebMvcTest(controllers = HubSettingsController.class, properties = "serv60.security.enabled=true")
@Import({
  SecurityConfiguration.class,
  OperatorAuthorization.class,
  HubAuthorization.class,
  HubSettingsControllerTest.Metrics.class
})
class HubSettingsControllerTest {
  @TestConfiguration
  static class Metrics {
    @Bean
    MeterRegistry metrics() {
      return new SimpleMeterRegistry();
    }
  }

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @MockitoBean HubSettingsService settings;
  @MockitoBean JwtDecoder decoder;
  static final UUID HUB = UUID.fromString("00000000-0000-0000-0000-000000000060");
  static final String URL = "/v1/hubs/" + HUB + "/settings";

  Update request() {
    return new Update(
        0,
        new Capacity(4, 1, 1, 1),
        java.util.stream.IntStream.rangeClosed(1, 7)
            .mapToObj(d -> new TradingDay(d, false, LocalTime.of(8, 0), LocalTime.of(17, 0)))
            .toList());
  }

  org.springframework.test.web.servlet.request.RequestPostProcessor operator() {
    return jwt()
        .jwt(
            t ->
                t.claim("roles", List.of("HUB_OPERATOR"))
                    .claim("hub_ids", List.of(HUB.toString())));
  }

  @Test
  void shouldRequireOperatorAndAuthenticatedHubMembershipForReadsAndWrites() throws Exception {
    for (var method : List.of("GET", "PUT")) {
      mvc.perform(
              org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(
                      org.springframework.http.HttpMethod.valueOf(method), URL)
                  .contentType("application/json")
                  .content(json.writeValueAsString(request())))
          .andExpect(status().isUnauthorized());
      mvc.perform(
              org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(
                      org.springframework.http.HttpMethod.valueOf(method), URL)
                  .with(jwt().jwt(t -> t.claim("hub_ids", List.of(HUB.toString()))))
                  .contentType("application/json")
                  .content(json.writeValueAsString(request())))
          .andExpect(status().isForbidden());
      mvc.perform(
              org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(
                      org.springframework.http.HttpMethod.valueOf(method), URL)
                  .with(
                      jwt()
                          .jwt(
                              t ->
                                  t.claim("roles", List.of("HUB_OPERATOR"))
                                      .claim("hub_ids", List.of(UUID.randomUUID().toString()))))
                  .contentType("application/json")
                  .content(json.writeValueAsString(request())))
          .andExpect(status().isForbidden());
    }
    verifyNoInteractions(settings);
  }

  @Test
  void shouldReadAndSaveWithoutCachingAndTranslateConflicts() throws Exception {
    var result =
        new Settings(
            HUB,
            "Test hub",
            "Africa/Johannesburg",
            true,
            1,
            request().capacity(),
            request().tradingHours(),
            List.of());
    when(settings.read(HUB)).thenReturn(result);
    when(settings.update(eq(HUB), any())).thenReturn(result);
    mvc.perform(get(URL).with(operator()))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.capacity.expressBays").value(4));
    mvc.perform(
            put(URL)
                .with(operator())
                .contentType("application/json")
                .content(json.writeValueAsString(request())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.revision").value(1));
    when(settings.update(eq(HUB), any())).thenThrow(new com.serv60.errors.Conflict("Reload settings"));
    mvc.perform(
            put(URL)
                .with(operator())
                .contentType("application/json")
                .content(json.writeValueAsString(request())))
        .andExpect(status().isConflict());
  }

  @Test
  void shouldRejectMalformedSettingsBeforeService() throws Exception {
    mvc.perform(
            put(URL)
                .with(operator())
                .contentType("application/json")
                .content("{\"revision\":0,\"capacity\":{\"expressBays\":-1},\"tradingHours\":[]}"))
        .andExpect(status().isBadRequest());
    verifyNoInteractions(settings);
  }
}
