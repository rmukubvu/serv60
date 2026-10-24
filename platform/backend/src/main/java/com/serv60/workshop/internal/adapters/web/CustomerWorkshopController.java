package com.serv60.workshop.internal.adapters.web;

import com.serv60.security.CustomerAuthorization;
import com.serv60.workshop.api.WorkshopContracts.Decision;
import com.serv60.workshop.api.WorkshopContracts.VisitProgress;
import com.serv60.workshop.internal.application.WorkshopService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/** The customer's view of their visit on the floor, and their answer to proposed extra work. */
@RestController
@RequestMapping("/v1/customer/appointments/{id}")
@Validated
public class CustomerWorkshopController {
  private final WorkshopService workshop;
  private final CustomerAuthorization identities;

  public CustomerWorkshopController(WorkshopService workshop, CustomerAuthorization identities) {
    this.workshop = workshop;
    this.identities = identities;
  }

  @GetMapping("/progress")
  public ResponseEntity<VisitProgress> progress(Authentication auth, @PathVariable UUID id) {
    return privateResponse(workshop.progress(identities.customerId(auth), id));
  }

  @PutMapping("/scope-changes/{scopeId}/decision")
  public ResponseEntity<VisitProgress> decide(
      Authentication auth,
      @PathVariable UUID id,
      @PathVariable UUID scopeId,
      @Valid @RequestBody Decision decision) {
    return privateResponse(
        workshop.decide(identities.customerId(auth), id, scopeId, decision.decision()));
  }

  private static <T> ResponseEntity<T> privateResponse(T body) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
  }
}
