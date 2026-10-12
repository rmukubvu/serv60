package com.serv60.concierge.internal.adapters.web;

import com.serv60.booking.api.CustomerBookingContracts.Appointment;
import com.serv60.concierge.api.ConciergeContracts.*;
import com.serv60.concierge.internal.application.*;
import com.serv60.security.CustomerAuthorization;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/customer")
public class ConciergeController {
  private final BookingAssistant assistant;
  private final ProposalStore proposals;
  private final CustomerAuthorization identities;

  public ConciergeController(
      BookingAssistant assistant, ProposalStore proposals, CustomerAuthorization identities) {
    this.assistant = assistant;
    this.proposals = proposals;
    this.identities = identities;
  }

  @PostMapping("/hubs/{hubId}/assistant")
  public ResponseEntity<Reply> reply(
      Authentication auth, @PathVariable UUID hubId, @Valid @RequestBody ChatRequest request) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(assistant.reply(identities.customerId(auth), hubId, request));
  }

  @PutMapping("/assistant/proposals/{id}/confirm")
  public ResponseEntity<Appointment> confirm(Authentication auth, @PathVariable UUID id) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(proposals.confirm(identities.customerId(auth), id));
  }

  @org.springframework.web.bind.annotation.ExceptionHandler(
      com.serv60.concierge.api.AssistantUnavailable.class)
  public org.springframework.http.ResponseEntity<java.util.Map<String, String>>
      assistantUnavailable(com.serv60.concierge.api.AssistantUnavailable error) {
    return org.springframework.http.ResponseEntity.status(503)
        .header("Retry-After", "30")
        .cacheControl(org.springframework.http.CacheControl.noStore())
        .body(java.util.Map.of("error", error.getMessage()));
  }
}
