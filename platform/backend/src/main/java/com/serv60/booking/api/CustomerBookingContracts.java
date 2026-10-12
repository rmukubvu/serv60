package com.serv60.booking.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class CustomerBookingContracts {
  private CustomerBookingContracts() {}

  public record NewVisit(
      @NotNull UUID hubId,
      @NotNull OffsetDateTime startsAt,
      @Size(max = 30) String complimentaryServiceCode,
      @NotNull @Valid BookingContracts.AppointmentContact contact) {}

  public record ChangeVisit(
      @NotNull OffsetDateTime startsAt,
      @Size(max = 30) String complimentaryServiceCode,
      @PositiveOrZero long revision) {}

  public record Appointment(
      UUID id,
      UUID hubId,
      String hubName,
      String timezone,
      String serviceCode,
      OffsetDateTime startsAt,
      OffsetDateTime endsAt,
      String status,
      String complimentaryServiceCode,
      BookingContracts.AppointmentContact contact,
      long revision,
      OffsetDateTime changeDeadline,
      boolean modifiable,
      OffsetDateTime expiresAt) {}

  public record Appointments(List<Appointment> items, OffsetDateTime nextStartsAt, UUID nextId) {}
}
