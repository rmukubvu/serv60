package com.serv60.identity.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import java.util.UUID;

public final class CustomerProfileContracts {
  private CustomerProfileContracts() {}

  public record Profile(
      UUID customerId,
      String customerName,
      String phoneNumber,
      List<Vehicle> vehicles) {}

  public record Vehicle(
      UUID id,
      String vehicleMake,
      String vehicleModel,
      String registration,
      boolean isDefault) {}

  public record SaveProfile(
      @NotBlank @Size(max = 160) String customerName,
      @NotBlank @Size(max = 32) String phoneNumber,
      @Valid List<@Valid SaveVehicle> vehicles) {}

  public record SaveVehicle(
      UUID id,
      @NotBlank @Size(max = 80) String vehicleMake,
      @NotBlank @Size(max = 80) String vehicleModel,
      @NotBlank @Size(max = 24) String registration,
      boolean isDefault) {}
}
