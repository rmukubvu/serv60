package com.serv60.roadside.internal.adapters.web;

import com.serv60.roadside.api.RoadsideContracts.*;
import com.serv60.roadside.internal.application.RoadsideMarketplace;
import com.serv60.security.CustomerAuthorization;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/customer/roadside")
@Validated
public class CustomerRoadsideController {
  private final RoadsideMarketplace marketplace;
  private final CustomerAuthorization identities;

  public CustomerRoadsideController(
      RoadsideMarketplace marketplace, CustomerAuthorization identities) {
    this.marketplace = marketplace;
    this.identities = identities;
  }

  @GetMapping("/nearby")
  public ResponseEntity<List<NearbyTruck>> nearby(
      Authentication auth,
      @RequestParam double latitude,
      @RequestParam double longitude) {
    identities.customerId(auth);
    return privateResponse(marketplace.nearby(latitude, longitude));
  }

  @PostMapping("/incidents")
  public ResponseEntity<IncidentDetail> create(
      Authentication auth,
      @Valid @RequestBody CreateGeoIncident body,
      @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 160) String key) {
    var customer = identities.customerId(auth);
    var request =
        new CreateGeoIncident(
            customer,
            body.vehicleId(),
            body.latitude(),
            body.longitude(),
            body.locationLabel(),
            body.description(),
            body.destinationLabel(),
            body.destinationLatitude(),
            body.destinationLongitude(),
            body.rideHomeRequested(),
            body.rideDropoffLabel(),
            body.rideDropoffLatitude(),
            body.rideDropoffLongitude());
    return ResponseEntity.status(HttpStatus.CREATED)
        .cacheControl(CacheControl.noStore())
        .body(marketplace.createGeo(request, key));
  }

  @GetMapping("/incidents/{id}")
  public ResponseEntity<IncidentDetail> detail(Authentication auth, @PathVariable UUID id) {
    var detail = marketplace.detail(id);
    if (!detail.customerId().equals(identities.customerId(auth))) {
      throw new IllegalArgumentException("Roadside incident not found");
    }
    return privateResponse(detail);
  }

  @PostMapping("/incidents/{id}/accept-quote")
  public ResponseEntity<IncidentDetail> acceptQuote(
      Authentication auth, @PathVariable UUID id, @Valid @RequestBody AcceptQuote body) {
    return privateResponse(
        marketplace.acceptQuote(identities.customerId(auth), id, body.quoteId()));
  }

  @PostMapping("/incidents/{id}/ride")
  public ResponseEntity<IncidentDetail> startRide(
      Authentication auth, @PathVariable UUID id, @Valid @RequestBody StartRide body) {
    return privateResponse(marketplace.startRide(identities.customerId(auth), id, body));
  }

  private static <T> ResponseEntity<T> privateResponse(T body) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
  }
}
