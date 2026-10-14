package com.serv60.identity.internal.adapters.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.serv60.identity.api.CustomerProfileContracts.Profile;
import com.serv60.identity.internal.application.CustomerProfileService;
import com.serv60.security.CustomerAuthorization;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CustomerProfileControllerTest {
  final UUID owner = UUID.randomUUID();
  final CustomerProfileService profiles = mock(CustomerProfileService.class);
  final CustomerAuthorization identities = mock(CustomerAuthorization.class);
  final org.springframework.test.web.servlet.MockMvc mvc =
      MockMvcBuilders.standaloneSetup(new CustomerProfileController(profiles, identities)).build();

  @Test
  void shouldReadAndSaveOnlyTheAuthenticatedCustomersProfileWithoutCaching() throws Exception {
    when(identities.customerId(any())).thenReturn(owner);
    when(profiles.get(owner)).thenReturn(new Profile(owner, "Alex", "+27", List.of()));
    when(profiles.save(eq(owner), any())).thenReturn(new Profile(owner, "Sam", "+27", List.of()));

    mvc.perform(get("/v1/customer/profile"))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store"))
        .andExpect(jsonPath("$.customerName").value("Alex"));
    mvc.perform(put("/v1/customer/profile").contentType("application/json")
            .content("{\"customerName\":\"Sam\",\"phoneNumber\":\"+27\",\"vehicles\":[]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.customerName").value("Sam"));
  }

  @Test
  void shouldRejectABlankNameBeforeSaving() throws Exception {
    when(identities.customerId(any())).thenReturn(owner);

    mvc.perform(put("/v1/customer/profile").contentType("application/json")
            .content("{\"customerName\":\"\",\"phoneNumber\":\"+27\"}"))
        .andExpect(status().isBadRequest());
    verify(profiles, never()).save(any(), any());
  }
}
