package com.serv60.ops.internal.adapters.web;

import com.serv60.ops.api.OpsInsightsContracts.OpsInsights;
import com.serv60.ops.internal.application.OpsInsightsService;
import com.serv60.security.HubAuthorization;
import com.serv60.security.OperatorAuthorization;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1")
@Validated
public class OpsInsightsController {
  private final OpsInsightsService insights;
  private final HubAuthorization hubs;
  private final OperatorAuthorization operators;

  public OpsInsightsController(
      OpsInsightsService insights, HubAuthorization hubs, OperatorAuthorization operators) {
    this.insights = insights;
    this.hubs = hubs;
    this.operators = operators;
  }

  @GetMapping("/hubs/{hubId}/ops-insights")
  public OpsInsights opsInsights(@PathVariable UUID hubId) {
    operators.require(currentAuthentication());
    hubs.require(hubId, currentAuthentication());
    return insights.forHub(hubId);
  }

  private Authentication currentAuthentication() {
    return SecurityContextHolder.getContext().getAuthentication();
  }
}
