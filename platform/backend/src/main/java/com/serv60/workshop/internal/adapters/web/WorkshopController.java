package com.serv60.workshop.internal.adapters.web;

import com.serv60.security.HubAuthorization;
import com.serv60.security.OperatorAuthorization;
import com.serv60.workshop.api.WorkshopContracts.*;
import com.serv60.workshop.internal.application.WorkshopService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/** The hub's live floor: today's visits, the stages staff record and the extra work they propose. */
@RestController
@RequestMapping("/v1/hubs/{hubId}")
@Validated
public class WorkshopController {
  static final String DEMO_OPERATOR = "demo-operator";

  private final WorkshopService workshop;
  private final HubAuthorization hubs;
  private final OperatorAuthorization operators;

  public WorkshopController(
      WorkshopService workshop, HubAuthorization hubs, OperatorAuthorization operators) {
    this.workshop = workshop;
    this.hubs = hubs;
    this.operators = operators;
  }

  @GetMapping("/floor")
  public List<FloorVisit> floor(Authentication auth, @PathVariable UUID hubId) {
    authorise(auth, hubId);
    return workshop.floor(hubId);
  }

  @PostMapping("/visits/{id}/check-in")
  public FloorVisit checkIn(
      Authentication auth,
      @PathVariable UUID hubId,
      @PathVariable UUID id,
      @Valid @RequestBody(required = false) CheckIn checkIn) {
    authorise(auth, hubId);
    return workshop.checkIn(hubId, id, checkIn == null ? null : checkIn.note(), recordedBy(auth));
  }

  @PostMapping("/visits/{id}/stages")
  public FloorVisit recordStage(
      Authentication auth,
      @PathVariable UUID hubId,
      @PathVariable UUID id,
      @Valid @RequestBody RecordStage stage) {
    authorise(auth, hubId);
    return workshop.recordStage(hubId, id, stage.stage(), stage.note(), recordedBy(auth));
  }

  @PostMapping("/visits/{id}/scope-changes")
  @ResponseStatus(HttpStatus.CREATED)
  public ScopeChange propose(
      Authentication auth,
      @PathVariable UUID hubId,
      @PathVariable UUID id,
      @Valid @RequestBody ProposeScopeChange proposal) {
    authorise(auth, hubId);
    return workshop.propose(hubId, id, proposal, recordedBy(auth));
  }

  private void authorise(Authentication auth, UUID hubId) {
    operators.require(auth);
    hubs.require(hubId, auth);
  }

  /** Who recorded it, for the audit trail; the local demo runs without a signed-in operator. */
  static String recordedBy(Authentication auth) {
    return auth == null || auth.getName() == null || auth.getName().isBlank()
        ? DEMO_OPERATOR
        : auth.getName();
  }
}
