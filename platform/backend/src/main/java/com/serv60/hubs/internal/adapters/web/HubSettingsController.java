package com.serv60.hubs.internal.adapters.web;

import com.serv60.hubs.api.HubSettingsContracts.*;
import com.serv60.hubs.internal.application.HubSettingsService;
import com.serv60.security.HubAuthorization;
import com.serv60.security.OperatorAuthorization;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/hubs/{hubId}/settings")
public class HubSettingsController {
  private final HubSettingsService settings;
  private final HubAuthorization hubs;
  private final OperatorAuthorization operators;
  private final MeterRegistry metrics;

  public HubSettingsController(
      HubSettingsService settings,
      HubAuthorization hubs,
      OperatorAuthorization operators,
      MeterRegistry metrics) {
    this.settings = settings;
    this.hubs = hubs;
    this.operators = operators;
    this.metrics = metrics;
  }

  /** Reads configuration only for an operator with membership in the requested hub. */
  @GetMapping
  public ResponseEntity<Settings> read(@PathVariable UUID hubId, Authentication auth) {
    authorize(hubId, auth);
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(settings.read(hubId));
  }

  /** Saves one versioned configuration after verifying both operator role and hub membership. */
  @PutMapping
  public ResponseEntity<Settings> update(
      @PathVariable UUID hubId, Authentication auth, @RequestBody Update request) {
    authorize(hubId, auth);
    var result = settings.update(hubId, request);
    metrics.counter("serv60.hub.settings.saves").increment();
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
  }

  private void authorize(UUID hub, Authentication auth) {
    operators.require(auth);
    hubs.require(hub, auth);
  }
}
