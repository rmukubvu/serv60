package com.serv60.workshop.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class WorkshopContracts {
  private WorkshopContracts() {}

  /** Minutes an eligible express visit is planned to take from check-in to ready. */
  public static final int TARGET_MINUTES = 60;

  public record StageEvent(String stage, OffsetDateTime recordedAt, String note) {}

  public record ScopeItem(
      @NotBlank @Size(max = 160) String description,
      @PositiveOrZero @Max(100_000_000) long amountMinor) {}

  public record ProposeScopeChange(
      @NotEmpty @Size(max = 20) List<@Valid ScopeItem> items, @Size(max = 500) String note) {}

  /** Extra work proposed to the customer; {@code status} is PROPOSED, APPROVED or DECLINED. */
  public record ScopeChange(
      UUID id,
      UUID visitId,
      String status,
      List<ScopeItem> items,
      String note,
      long totalMinor,
      String currency,
      OffsetDateTime proposedAt,
      OffsetDateTime decidedAt) {}

  public record Decision(@NotBlank @Pattern(regexp = "APPROVE|DECLINE") String decision) {}

  public record RecordStage(@NotNull VisitStage stage, @Size(max = 300) String note) {}

  public record CheckIn(@Size(max = 300) String note) {}

  /**
   * What the customer sees. {@code stage} is null until check-in; {@code elapsedMinutes} runs from
   * check-in and freezes once the car is ready. {@code voucherCode} is issued at check-in.
   */
  public record VisitProgress(
      UUID visitId,
      String hubName,
      OffsetDateTime startsAt,
      String complimentaryServiceCode,
      String stage,
      OffsetDateTime checkedInAt,
      OffsetDateTime readyAt,
      OffsetDateTime handedOverAt,
      int targetMinutes,
      Integer elapsedMinutes,
      List<StageEvent> events,
      List<ScopeChange> scopeChanges,
      String voucherCode) {}

  /** One row of the operator's floor board. {@code nextStages} are the stages that may be recorded. */
  public record FloorVisit(
      UUID visitId,
      OffsetDateTime startsAt,
      OffsetDateTime endsAt,
      String bookingStatus,
      String stage,
      OffsetDateTime checkedInAt,
      OffsetDateTime readyAt,
      OffsetDateTime handedOverAt,
      Integer elapsedMinutes,
      int targetMinutes,
      String customerName,
      String vehicle,
      String registration,
      String complimentaryServiceCode,
      String bayCode,
      int pendingScopeChanges,
      long approvedExtrasMinor,
      List<String> nextStages) {}
}
