package com.serv60.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.serv60.booking.internal.adapters.web.BookingController;
import com.serv60.booking.internal.application.BookingService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import java.util.Optional;

@WebMvcTest(controllers = BookingController.class, properties = "serv60.security.enabled=true")
@Import(SecurityConfiguration.class)
class SecurityConfigurationTest {
  @Autowired MockMvc mvc;
  @MockBean BookingService bookings;
  @MockBean JwtDecoder jwtDecoder;

  @org.junit.jupiter.api.BeforeEach
  void setUp() {
    when(bookings.findHold(any())).thenReturn(Optional.empty());
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
}
