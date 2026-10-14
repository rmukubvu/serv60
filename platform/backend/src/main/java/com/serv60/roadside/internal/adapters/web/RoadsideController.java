package com.serv60.roadside.internal.adapters.web;

import com.serv60.roadside.api.RoadsideContracts;
import com.serv60.roadside.internal.application.RoadsideService;
import com.serv60.security.CustomerAuthorization;
import com.serv60.security.HubAuthorization;
import com.serv60.security.OperatorAuthorization;
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
  private final com.serv60.roadside.internal.application.RoadsideMarketplace marketplace;
  private final CustomerAuthorization authorization;
  private final OperatorAuthorization operatorAuthorization;
  private final HubAuthorization hubAuthorization;

  public RoadsideController(
      RoadsideService roadside,
      com.serv60.roadside.internal.application.RoadsideMarketplace marketplace,
      CustomerAuthorization authorization,
      OperatorAuthorization operatorAuthorization,
      HubAuthorization hubAuthorization) {
    this.roadside = roadside;
    this.marketplace = marketplace;
    this.authorization = authorization;
    this.operatorAuthorization = operatorAuthorization;
    this.hubAuthorization = hubAuthorization;
  }

  @PostMapping
  @ResponseStatus(HttpStatus.CREATED)
  public RoadsideContracts.Incident create(@RequestHeader("Idempotency-Key") @NotBlank String key,
                                           @Valid @RequestBody RoadsideContracts.CreateIncident request) {
    // No hub is known yet for this legacy path, so only the customer or an ADMIN may create.
    authorization.requireCustomerOrHubOperator(request.customerId(), null, currentAuthentication());
    return roadside.create(request, key);
  }

  @GetMapping
  public List<RoadsideContracts.Incident> list(
      @RequestParam UUID customerId,
      @RequestParam(defaultValue = "20") int limit) {
    authorization.require(customerId, currentAuthentication());
    return roadside.list(customerId, limit);
  }

  @GetMapping("/dispatch")
  public List<RoadsideContracts.Incident> dispatchQueue(
      @RequestParam UUID hubId, @RequestParam(defaultValue = "50") int limit) {
    requireHubOperator(hubId);
    return roadside.listDispatchable(hubId, limit);
  }

  @GetMapping("/dispatch/board")
  public List<RoadsideContracts.DispatchBoardItem> dispatchBoard(
      @RequestParam UUID hubId, @RequestParam(defaultValue = "50") int limit) {
    requireHubOperator(hubId);
    return marketplace.dispatchBoard(hubId, limit);
  }

  @GetMapping("/{id}")
  public ResponseEntity<RoadsideContracts.Incident> find(@PathVariable UUID id) {
    return roadside.find(id).map(incident -> {
      authorization.requireCustomerOrHubOperator(incident.customerId(), roadside.hubOf(incident.id()), currentAuthentication());
      return ResponseEntity.ok(incident);
    }).orElseGet(() -> ResponseEntity.notFound().build());
  }

  @PostMapping("/{id}/assignment")
  public RoadsideContracts.RecoveryAssignment assign(
      @PathVariable UUID id, @Valid @RequestBody RoadsideContracts.AssignRecovery request) {
    hubAuthorization.requireOperatorOf(roadside.hubOf(id), currentAuthentication());
    return roadside.assign(id, request);
  }

  @PostMapping("/{id}/cancel")
  public RoadsideContracts.Incident cancel(@PathVariable UUID id) {
    var incident = roadside.find(id)
        .orElseThrow(() -> new com.serv60.errors.NotFound("Roadside incident not found"));
    authorization.requireCustomerOrHubOperator(incident.customerId(), roadside.hubOf(id), currentAuthentication());
    return roadside.cancel(id);
  }

  /** Customer locations are private: an operator sees only incidents for hubs they belong to. */
  private void requireHubOperator(UUID hubId) {
    operatorAuthorization.require(currentAuthentication());
    hubAuthorization.require(hubId, currentAuthentication());
  }

  private Authentication currentAuthentication() {
    return SecurityContextHolder.getContext().getAuthentication();
  }
}
