package com.serv60.roadside.internal.adapters.web;

import com.serv60.roadside.api.RoadsideContracts;
import com.serv60.roadside.internal.application.RoadsideService;
import com.serv60.security.CustomerAuthorization;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.util.UUID;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.validation.annotation.Validated;

@RestController
@RequestMapping("/v1/roadside/incidents")
@Validated
public class RoadsideController {
  private final RoadsideService roadside;
  private final CustomerAuthorization authorization;

  public RoadsideController(RoadsideService roadside, CustomerAuthorization authorization) {
    this.roadside = roadside;
    this.authorization = authorization;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public RoadsideContracts.Incident create(@RequestHeader("Idempotency-Key") @NotBlank String key,
                                           @Valid @RequestBody RoadsideContracts.CreateIncident request) {
    authorization.require(request.customerId(), currentAuthentication());
    return roadside.create(request, key);
  }

  @GetMapping
  public List<RoadsideContracts.Incident> list(
      @RequestParam UUID customerId,
      @RequestParam(defaultValue = "20") int limit) {
    authorization.require(customerId, currentAuthentication());
    return roadside.list(customerId, limit);
  }

  @GetMapping("/{id}")
  public ResponseEntity<RoadsideContracts.Incident> find(@PathVariable UUID id) {
    return roadside.find(id).map(incident -> {
      authorization.require(incident.customerId(), currentAuthentication());
      return ResponseEntity.ok(incident);
    }).orElseGet(() -> ResponseEntity.notFound().build());
  }

  @PostMapping("/{id}/cancel")
  public RoadsideContracts.Incident cancel(@PathVariable UUID id) {
    var incident = roadside.find(id)
        .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
            HttpStatus.NOT_FOUND, "Roadside incident not found"));
    authorization.require(incident.customerId(), currentAuthentication());
    return roadside.cancel(id);
  }

  private Authentication currentAuthentication() {
    return SecurityContextHolder.getContext().getAuthentication();
  }
}
