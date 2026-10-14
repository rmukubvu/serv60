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
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import com.serv60.security.HubAuthorization;
import com.serv60.security.CustomerAuthorization;
import com.serv60.security.OperatorAuthorization;

@RestController
@RequestMapping("/v1")
@Validated
public class BookingController {
  private final BookingService bookings;
  private final HubAuthorization authorization;
  private final CustomerAuthorization customerAuthorization;
  private final OperatorAuthorization operatorAuthorization;
  public BookingController(BookingService bookings, HubAuthorization authorization,
                           CustomerAuthorization customerAuthorization, OperatorAuthorization operatorAuthorization) {
    this.bookings = bookings;
    this.authorization = authorization;
    this.customerAuthorization = customerAuthorization;
    this.operatorAuthorization = operatorAuthorization;
  }

  @GetMapping("/hubs/{hubId}/availability")
  public List<BookingContracts.Availability> availability(@PathVariable UUID hubId,
                                                            @RequestParam String serviceCode,
                                                            @RequestParam OffsetDateTime from,
                                                            @RequestParam OffsetDateTime to,
                                                            @RequestParam(required = false) String complimentaryServiceCode) {
    authorization.require(hubId, currentAuthentication());
    return complimentaryServiceCode == null ? bookings.availability(hubId, serviceCode, from, to)
        : bookings.availability(hubId, serviceCode, from, to, complimentaryServiceCode);
  }

  @GetMapping("/hubs/{hubId}/parts-readiness")
  public List<BookingContracts.PartsReadiness> partsReadiness(@PathVariable UUID hubId,
                                                               @RequestParam(defaultValue = "50") int limit) {
    authorization.require(hubId, currentAuthentication());
    return bookings.partsReadiness(hubId, limit);
  }

  @GetMapping("/hubs/{hubId}/calendar")
  public List<BookingContracts.CalendarEntry> calendar(@PathVariable UUID hubId,
                                                        @RequestParam OffsetDateTime from,
                                                        @RequestParam OffsetDateTime to) {
    authorization.require(hubId, currentAuthentication());
    return bookings.calendar(hubId, from, to);
  }

  @GetMapping("/hubs/{hubId}/appointments/{id}/details")
  public ResponseEntity<BookingContracts.AppointmentDetails> details(@PathVariable UUID hubId, @PathVariable UUID id) {
    operatorAuthorization.require(currentAuthentication());
    authorization.require(hubId, currentAuthentication());
    return bookings.appointmentDetails(hubId, id)
        .map(details -> ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore()).body(details))
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  @GetMapping("/visit-holds/{id}")
  public ResponseEntity<BookingContracts.Hold> findHold(@PathVariable UUID id) {
    return bookings.findHold(id).map(hold -> {
      customerAuthorization.requireCustomerOrHubOperator(hold.customerId(), hold.hubId(), currentAuthentication());
      return ResponseEntity.ok(hold);
    })
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  @PostMapping("/visit-holds")
  @ResponseStatus(HttpStatus.CREATED)
  public BookingContracts.Hold hold(@RequestHeader("Idempotency-Key") @NotBlank String key,
                                    @Valid @RequestBody BookingContracts.CreateHold request) {
    operatorAuthorization.require(currentAuthentication());
    authorization.require(request.hubId(), currentAuthentication());
    return bookings.createOperatorHold(request, key);
  }

  @PostMapping("/visit-holds/{id}/confirm")
  public BookingContracts.Hold confirm(@PathVariable UUID id) {
    operatorAuthorization.require(currentAuthentication());
    var hold = loadHold(id);
    authorization.require(hold.hubId(), currentAuthentication());
    // Operators may confirm without payment; record who did it.
    var auth = currentAuthentication();
    return bookings.confirm(id, "operator:" + (auth == null ? "local" : auth.getName()));
  }

  @PostMapping("/visit-holds/{id}/cancel")
  public BookingContracts.Hold cancel(@PathVariable UUID id) {
    operatorAuthorization.require(currentAuthentication());
    var hold = loadHold(id);
    authorization.require(hold.hubId(), currentAuthentication());
    return bookings.cancel(id);
  }

  private BookingContracts.Hold loadHold(UUID id) {
    return bookings.findHold(id).orElseThrow(() -> new com.serv60.errors.NotFound("Visit hold not found"));
  }

  private Authentication currentAuthentication() {
    return SecurityContextHolder.getContext().getAuthentication();
  }
}
