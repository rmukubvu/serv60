package com.serv60.booking.internal.adapters.web;

import com.serv60.booking.api.BookingContracts;
import com.serv60.booking.api.CustomerBookingContracts.*;
import com.serv60.booking.internal.application.CustomerBookingService;
import com.serv60.hubs.api.HubContracts.Hub;
import com.serv60.hubs.api.HubDiscovery;
import com.serv60.security.CustomerAuthorization;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/customer")
@Validated
public class CustomerBookingController {
  private final CustomerBookingService bookings;
  private final HubDiscovery hubs;
  private final CustomerAuthorization identities;

  public CustomerBookingController(
      CustomerBookingService bookings, HubDiscovery hubs, CustomerAuthorization identities) {
    this.bookings = bookings;
    this.hubs = hubs;
    this.identities = identities;
  }

  @GetMapping("/hubs")
  public ResponseEntity<List<Hub>> nearby(
      Authentication auth,
      @RequestParam double latitude,
      @RequestParam double longitude,
      @RequestParam(defaultValue = "") String q) {
    identities.customerId(auth);
    return privateResponse(hubs.nearby(latitude, longitude, q));
  }

  @GetMapping("/hubs/{hubId}")
  public ResponseEntity<Hub> hub(Authentication auth, @PathVariable UUID hubId) {
    identities.customerId(auth);
    return privateResponse(hubs.require(hubId));
  }

  @PostMapping("/appointments/{id}/payment")
  public ResponseEntity<com.serv60.payment.api.PaymentContracts.PaymentOrder> payment(
      Authentication auth, @PathVariable UUID id) {
    return privateResponse(bookings.payment(identities.customerId(auth), id));
  }

  @GetMapping("/hubs/{hubId}/slots")
  public ResponseEntity<List<BookingContracts.Availability>> slots(
      Authentication auth,
      @PathVariable UUID hubId,
      @RequestParam LocalDate date,
      @RequestParam(required = false) String complimentaryServiceCode,
      @RequestParam(required = false) UUID replacing) {
    return privateResponse(
        bookings.slots(
            identities.customerId(auth), hubId, date, complimentaryServiceCode, replacing));
  }

  @GetMapping("/appointments")
  public ResponseEntity<Appointments> upcoming(
      Authentication auth,
      @RequestParam(defaultValue = "20") int limit,
      @RequestParam(required = false) OffsetDateTime after,
      @RequestParam(required = false) UUID afterId) {
    return privateResponse(bookings.upcoming(identities.customerId(auth), limit, after, afterId));
  }

  @PostMapping("/appointments")
  public ResponseEntity<BookingContracts.Hold> create(
      Authentication auth,
      @Valid @RequestBody NewVisit visit,
      @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 160) String key) {
    return ResponseEntity.status(HttpStatus.CREATED)
        .cacheControl(CacheControl.noStore())
        .body(bookings.create(identities.customerId(auth), visit, key));
  }

  @PutMapping("/appointments/{id}")
  public ResponseEntity<Appointment> change(
      Authentication auth,
      @PathVariable UUID id,
      @Valid @RequestBody ChangeVisit visit,
      @RequestHeader("Idempotency-Key") @NotBlank @Size(max = 160) String key) {
    return privateResponse(bookings.change(identities.customerId(auth), id, visit, key));
  }

  @PutMapping("/appointments/{id}/confirm-payment")
  public ResponseEntity<Appointment> confirmPaid(Authentication auth, @PathVariable UUID id) {
    return privateResponse(bookings.confirmPaid(identities.customerId(auth), id));
  }

  @PostMapping("/appointments/{id}/cancel")
  public ResponseEntity<Appointment> cancel(Authentication auth, @PathVariable UUID id) {
    return privateResponse(bookings.cancel(identities.customerId(auth), id));
  }

  private static <T> ResponseEntity<T> privateResponse(T body) {
    return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body);
  }
}
