package com.serv60.booking.internal.adapters.web;

import com.serv60.booking.api.BookingContracts;
import com.serv60.booking.internal.application.BookingService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import com.serv60.security.HubAuthorization;
import com.serv60.security.CustomerAuthorization;

@RestController
@RequestMapping("/v1")
@Validated
public class BookingController {
  private final BookingService bookings;
  private final HubAuthorization authorization;
  private final CustomerAuthorization customerAuthorization;
  public BookingController(BookingService bookings, HubAuthorization authorization,
                           CustomerAuthorization customerAuthorization) {
    this.bookings = bookings;
    this.authorization = authorization;
    this.customerAuthorization = customerAuthorization;
  }

  @GetMapping("/hubs/{hubId}/availability")
  public List<BookingContracts.Availability> availability(@PathVariable UUID hubId,
                                                            @RequestParam String serviceCode,
                                                            @RequestParam OffsetDateTime from,
                                                            @RequestParam OffsetDateTime to) {
    authorization.require(hubId, currentAuthentication());
    return bookings.availability(hubId, serviceCode, from, to);
  }

  @GetMapping("/hubs/{hubId}/parts-readiness")
  public List<BookingContracts.PartsReadiness> partsReadiness(@PathVariable UUID hubId,
                                                               @RequestParam(defaultValue = "50") int limit) {
    authorization.require(hubId, currentAuthentication());
    return bookings.partsReadiness(hubId, limit);
  }

  @GetMapping("/visit-holds/{id}")
  public ResponseEntity<BookingContracts.Hold> findHold(@PathVariable UUID id) {
    return bookings.findHold(id).map(hold -> {
      authorization.require(hold.hubId(), currentAuthentication());
      customerAuthorization.require(hold.customerId(), currentAuthentication());
      return ResponseEntity.ok(hold);
    })
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  @PostMapping("/visit-holds")
  @ResponseStatus(HttpStatus.CREATED)
  public BookingContracts.Hold hold(@RequestHeader("Idempotency-Key") @NotBlank String key,
                                    @Valid @RequestBody BookingContracts.CreateHold request) {
    authorization.require(request.hubId(), currentAuthentication());
    customerAuthorization.require(request.customerId(), currentAuthentication());
    return bookings.createHold(request, key);
  }

  @PostMapping("/visit-holds/{id}/confirm")
  public BookingContracts.Hold confirm(@PathVariable UUID id) {
    var hold = loadHold(id);
    authorization.require(hold.hubId(), currentAuthentication());
    customerAuthorization.require(hold.customerId(), currentAuthentication());
    return bookings.confirm(id);
  }

  @PostMapping("/visit-holds/{id}/cancel")
  public BookingContracts.Hold cancel(@PathVariable UUID id) {
    var hold = loadHold(id);
    authorization.require(hold.hubId(), currentAuthentication());
    customerAuthorization.require(hold.customerId(), currentAuthentication());
    return bookings.cancel(id);
  }

  private BookingContracts.Hold loadHold(UUID id) {
    return bookings.findHold(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Visit hold not found"));
  }

  private Authentication currentAuthentication() {
    return SecurityContextHolder.getContext().getAuthentication();
  }
}
