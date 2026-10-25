package com.serv60.workshop.internal.adapters.web;

import com.serv60.security.CustomerAuthorization;
import com.serv60.workshop.api.WorkshopContracts.ServiceRecord;
import com.serv60.workshop.internal.application.WorkshopService;
import java.util.List;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The signed-in customer's handed-over visits and their invoices, newest first. */
@RestController
@RequestMapping("/v1/customer/service-history")
public class ServiceHistoryController {
  private final WorkshopService workshop;
  private final CustomerAuthorization identities;

  public ServiceHistoryController(WorkshopService workshop, CustomerAuthorization identities) {
    this.workshop = workshop;
    this.identities = identities;
  }

  @GetMapping
  public ResponseEntity<List<ServiceRecord>> history(
      Authentication auth, @RequestParam(defaultValue = "20") int limit) {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore())
        .body(workshop.serviceHistory(identities.customerId(auth), limit));
  }
}
