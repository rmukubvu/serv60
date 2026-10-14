package com.serv60.roadside.api;

import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class RoadsideContracts {
  private RoadsideContracts() {}

  public record CreateIncident(UUID customerId, UUID vehicleId, String location,
                               String description) {}

  public record Incident(UUID id, UUID customerId, UUID vehicleId, String status,
                         String location, String description, OffsetDateTime createdAt) {}

  public record RecoveryOption(String provider, String quoteReference, long amountMinor,
                               String currency, int estimatedMinutes) {}

  public record AssignRecovery(String provider, String quoteReference, long amountMinor,
                               String currency, int estimatedMinutes, boolean rideHome) {}

  public record RecoveryAssignment(UUID id, UUID incidentId, String provider,
                                   String quoteReference, long amountMinor, String currency,
                                   int estimatedMinutes, boolean rideHome, String status,
                                   OffsetDateTime createdAt) {}

  public record NearbyTruck(UUID partnerId, String name, double latitude, double longitude,
                            int etaMinutes, double distanceKm) {}

  public record CreateGeoIncident(
      @NotNull UUID customerId,
      UUID vehicleId,
      @NotNull @DecimalMin("-90") @DecimalMax("90") Double latitude,
      @NotNull @DecimalMin("-180") @DecimalMax("180") Double longitude,
      @NotBlank @Size(max = 500) String locationLabel,
      @NotBlank @Size(max = 2000) String description,
      @Size(max = 240) String destinationLabel,
      Double destinationLatitude,
      Double destinationLongitude,
      boolean rideHomeRequested,
      @Size(max = 240) String rideDropoffLabel,
      Double rideDropoffLatitude,
      Double rideDropoffLongitude) {}

  public record Quote(
      UUID id,
      UUID partnerId,
      String partnerName,
      long towAmountMinor,
      long rideAmountMinor,
      long totalAmountMinor,
      String currency,
      int etaMinutes,
      double distanceKm,
      OffsetDateTime expiresAt) {}

  public record RideLeg(
      UUID id,
      String provider,
      String status,
      long amountMinor,
      String currency,
      String dropoffLabel,
      String trackingUrl,
      String pinCode) {}

  public record IncidentDetail(
      UUID id,
      UUID customerId,
      String status,
      String location,
      String description,
      Double latitude,
      Double longitude,
      String destinationLabel,
      boolean rideHomeRequested,
      String truckLabel,
      Double truckLatitude,
      Double truckLongitude,
      Integer etaMinutes,
      List<Quote> quotes,
      RideLeg ride,
      OffsetDateTime createdAt) {}

  public record AcceptQuote(@NotNull UUID quoteId) {}

  public record StartRide(
      @NotBlank @Size(max = 240) String dropoffLabel,
      Double dropoffLatitude,
      Double dropoffLongitude) {}


  public record DispatchBoardItem(
      Incident incident,
      Double latitude,
      Double longitude,
      String truckLabel,
      Double truckLatitude,
      Double truckLongitude,
      Integer etaMinutes,
      String rideStatus,
      boolean rideHomeRequested,
      UUID hubId,
      String nearestPartnerName,
      Integer nearestPartnerEtaMinutes,
      Double nearestPartnerDistanceKm) {}
}
