package com.serv60.booking.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.UUID;

public final class BookingContracts {
  private BookingContracts() {}

  public record Availability(UUID hubId, String serviceCode, OffsetDateTime startsAt,
                             OffsetDateTime endsAt, String readinessPolicy) {}

  public record CreateHold(@NotNull UUID hubId, @NotNull UUID customerId, UUID vehicleId,
                           @NotBlank @Size(max = 80) String serviceCode,
                           @NotNull OffsetDateTime startsAt, @NotNull OffsetDateTime endsAt,
                           @Size(max = 32) String policy,
                           @Size(max = 30) String complimentaryServiceCode,
                           @Valid AppointmentContact contact) {
    public CreateHold(UUID hubId, UUID customerId, UUID vehicleId, String serviceCode,
                      OffsetDateTime startsAt, OffsetDateTime endsAt, String policy, String complimentaryServiceCode) {
      this(hubId, customerId, vehicleId, serviceCode, startsAt, endsAt, policy, complimentaryServiceCode, null);
    }
    public CreateHold(UUID hubId, UUID customerId, UUID vehicleId, String serviceCode,
                      OffsetDateTime startsAt, OffsetDateTime endsAt, String policy) {
      this(hubId, customerId, vehicleId, serviceCode, startsAt, endsAt, policy, null);
    }
  }

  public record Hold(UUID id, UUID hubId, UUID customerId, String serviceCode,
                     OffsetDateTime startsAt, OffsetDateTime endsAt, String status,
                     String policy, OffsetDateTime expiresAt, String complimentaryServiceCode) {
    public Hold(UUID id, UUID hubId, UUID customerId, String serviceCode,
                OffsetDateTime startsAt, OffsetDateTime endsAt, String status,
                String policy, OffsetDateTime expiresAt) {
      this(id, hubId, customerId, serviceCode, startsAt, endsAt, status, policy, expiresAt, null);
    }
  }

  /** Contact and car information supplied for this visit, separate from customer profiles. */
  public record AppointmentContact(@NotBlank @Size(max = 160) String customerName,
                                   @NotBlank @Size(max = 32) String phoneNumber,
                                   @Size(max = 80) String vehicleMake,
                                   @Size(max = 80) String vehicleModel,
                                   @Size(max = 24) String registration,
                                   @Email @Size(max = 254) String email) {
    public AppointmentContact(String customerName, String phoneNumber, String vehicleMake,
                              String vehicleModel, String registration) {
      this(customerName, phoneNumber, vehicleMake, vehicleModel, registration, null);
    }
  }

  public record AppointmentDetails(UUID holdId, AppointmentContact contact,
                                   String complimentaryServiceCode) {}

  public record PartsReadiness(String sku, int usableQuantity, int reservedQuantity,
                               int pendingDemand) {}

  public record CalendarEntry(UUID allocationId, UUID holdId, UUID resourceId,
                              String resourceCode, String serviceCode,
                              OffsetDateTime startsAt, OffsetDateTime endsAt,
                              String status) {}
}
