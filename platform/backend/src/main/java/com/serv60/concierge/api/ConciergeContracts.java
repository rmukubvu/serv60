package com.serv60.concierge.api;

import com.serv60.booking.api.BookingContracts.AppointmentContact;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class ConciergeContracts {
  private ConciergeContracts() {}

  public record Turn(
      @Pattern(regexp = "user|assistant") @NotBlank String role,
      @NotBlank @Size(max = 2000) String content) {}

  public record ChatRequest(@NotEmpty @Size(max = 16) List<@NotNull @Valid Turn> messages) {}

  public record Reply(String message, Proposal proposal) {}

  public record Proposal(
      UUID id,
      String kind,
      UUID hubId,
      String hubName,
      UUID appointmentId,
      OffsetDateTime startsAt,
      OffsetDateTime endsAt,
      String complimentaryServiceCode,
      AppointmentContact contact,
      OffsetDateTime expiresAt) {}
}
