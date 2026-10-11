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
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1")
@Validated
public class BookingController {
  private final BookingService bookings;
  public BookingController(BookingService bookings) { this.bookings = bookings; }

  @GetMapping("/hubs/{hubId}/availability")
  public List<BookingContracts.Availability> availability(@PathVariable UUID hubId,
                                                            @RequestParam String serviceCode,
                                                            @RequestParam OffsetDateTime from,
                                                            @RequestParam OffsetDateTime to) {
    return bookings.availability(hubId, serviceCode, from, to);
  }

  @GetMapping("/hubs/{hubId}/parts-readiness")
  public List<BookingContracts.PartsReadiness> partsReadiness(@PathVariable UUID hubId,
                                                               @RequestParam(defaultValue = "50") int limit) {
    return bookings.partsReadiness(hubId, limit);
  }

  @GetMapping("/visit-holds/{id}")
  public ResponseEntity<BookingContracts.Hold> findHold(@PathVariable UUID id) {
    return bookings.findHold(id).map(ResponseEntity::ok)
        .orElseGet(() -> ResponseEntity.notFound().build());
  }

  @PostMapping("/visit-holds")
  @ResponseStatus(HttpStatus.CREATED)
  public BookingContracts.Hold hold(@RequestHeader("Idempotency-Key") @NotBlank String key,
                                    @Valid @RequestBody BookingContracts.CreateHold request) {
    return bookings.createHold(request, key);
  }

  @PostMapping("/visit-holds/{id}/confirm")
  public BookingContracts.Hold confirm(@PathVariable UUID id) { return bookings.confirm(id); }
}
