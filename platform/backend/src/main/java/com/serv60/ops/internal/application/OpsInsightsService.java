package com.serv60.ops.internal.application;

import static com.serv60.ops.api.OpsInsightsContracts.*;

import com.serv60.booking.api.BookingContracts;
import com.serv60.booking.api.HubBookingBoard;
import com.serv60.hubs.api.HubConfiguration;
import com.serv60.hubs.api.HubSettingsContracts;
import com.serv60.roadside.api.RoadsideContracts.DispatchBoardItem;
import com.serv60.roadside.api.RoadsideDispatch;
import java.time.*;
import java.util.*;
import org.springframework.stereotype.Service;

/**
 * Builds hub-scoped operator insights from live calendar, parts, and roadside state. Scoring is
 * deterministic so the board stays useful when the language model is offline.
 */
@Service
public class OpsInsightsService {
  private final HubBookingBoard bookings;
  private final HubConfiguration hubs;
  private final RoadsideDispatch roadside;
  private final Clock clock;

  public OpsInsightsService(
      HubBookingBoard bookings,
      HubConfiguration hubs,
      RoadsideDispatch roadside,
      Clock clock) {
    this.bookings = bookings;
    this.hubs = hubs;
    this.roadside = roadside;
    this.clock = clock;
  }

  public OpsInsights forHub(UUID hubId) {
    var settings = hubs.read(hubId);
    ZoneId zone = ZoneId.of(settings.timezone());
    ZonedDateTime now = ZonedDateTime.now(clock).withZoneSameInstant(zone);
    OffsetDateTime from = now.toLocalDate().atStartOfDay(zone).toOffsetDateTime();
    OffsetDateTime to = from.plusDays(2);

    List<BookingContracts.CalendarEntry> calendar = bookings.calendar(hubId, from, to);
    List<BookingContracts.PartsReadiness> parts = bookings.partsReadiness(hubId, 50);
    List<DispatchBoardItem> board = roadside.dispatchBoard(hubId, 50);

    List<FloorAlert> alerts = new ArrayList<>();
    alerts.addAll(paymentAlerts(bookings.paymentAttention(hubId, 20)));
    alerts.addAll(partsAlerts(parts));
    alerts.addAll(floorPressureAlerts(calendar, settings, now));
    alerts.addAll(lateRiskAlerts(calendar, now));

    List<DispatchPriority> dispatch =
        board.stream()
            .map(item -> rankDispatch(item, Instant.from(now)))
            .sorted(Comparator.comparingInt(DispatchPriority::priorityScore).reversed())
            .toList();

    return new OpsInsights(alerts, dispatch);
  }

  /** Paid-without-booking and refund-owed problems come first: each is a customer out of pocket. */
  static List<FloorAlert> paymentAlerts(List<BookingContracts.PaymentAttention> items) {
    return items.stream()
        .map(item -> new FloorAlert(
            "CRITICAL",
            item.kind(),
            item.kind().equals("REFUND_REQUIRED") ? "Refund owed for a cancelled paid visit"
                : "Customer paid but the visit is not booked",
            "Visit " + item.holdId().toString().substring(0, 8)
                + (item.amountMinor() == null ? "" : String.format(Locale.ROOT, " · R%.2f", item.amountMinor() / 100.0))
                + (item.reason() == null ? "" : " · " + item.reason().replace('_', ' '))
                + ". Refund in Yoco or rebook the customer; this clears once the order is refunded."))
        .toList();
  }

  static List<FloorAlert> partsAlerts(List<BookingContracts.PartsReadiness> parts) {
    List<FloorAlert> alerts = new ArrayList<>();
    for (var part : parts) {
      int available = part.usableQuantity() - part.reservedQuantity();
      if (part.pendingDemand() <= 0) continue;
      if (available < part.pendingDemand()) {
        alerts.add(
            new FloorAlert(
                "CRITICAL",
                "PARTS_SHORTAGE",
                part.sku() + " short for upcoming hours",
                available
                    + " available against "
                    + part.pendingDemand()
                    + " pending demand — resolve stock before those holds start."));
      } else if (available - part.pendingDemand() <= 2) {
        alerts.add(
            new FloorAlert(
                "WARN",
                "PARTS_TIGHT",
                part.sku() + " nearly committed",
                available
                    + " available with "
                    + part.pendingDemand()
                    + " pending — thin buffer for walk-ins."));
      }
    }
    return alerts;
  }

  static List<FloorAlert> floorPressureAlerts(
      List<BookingContracts.CalendarEntry> calendar,
      HubSettingsContracts.Settings settings,
      ZonedDateTime now) {
    int expressBays = Math.max(1, settings.capacity().expressBays());
    Set<String> lifestyle = Set.copyOf(com.serv60.hubs.api.ServiceCode.complimentaryCodes());
    Map<Integer, Long> bayLoadByHour = new HashMap<>();
    for (var entry : calendar) {
      if (lifestyle.contains(entry.serviceCode())) continue;
      ZonedDateTime start = entry.startsAt().atZoneSameInstant(now.getZone());
      LocalDate day = start.toLocalDate();
      if (day.isBefore(now.toLocalDate()) || day.isAfter(now.toLocalDate().plusDays(1))) {
        continue;
      }
      int hourKey = day.getDayOfYear() * 100 + start.getHour();
      bayLoadByHour.merge(hourKey, 1L, Long::sum);
    }
    List<FloorAlert> alerts = new ArrayList<>();
    bayLoadByHour.forEach(
        (key, count) -> {
          if (count > expressBays) {
            int hour = key % 100;
            alerts.add(
                new FloorAlert(
                    "WARN",
                    "FLOOR_PRESSURE",
                    "Service floor overbooked around " + String.format("%02d:00", hour),
                    count
                        + " automotive allocations against "
                        + expressBays
                        + " express bays — move a HELD visit or open a lifestyle wait."));
          }
        });
    return alerts;
  }

  static List<FloorAlert> lateRiskAlerts(
      List<BookingContracts.CalendarEntry> calendar, ZonedDateTime now) {
    List<FloorAlert> alerts = new ArrayList<>();
    long heldSoon =
        calendar.stream()
            .filter(entry -> "HELD".equals(entry.status()))
            .filter(
                entry -> {
                  Duration until = Duration.between(now.toOffsetDateTime(), entry.startsAt());
                  return !until.isNegative() && until.toMinutes() <= 90;
                })
            .map(BookingContracts.CalendarEntry::holdId)
            .distinct()
            .count();
    if (heldSoon > 0) {
      alerts.add(
          new FloorAlert(
              "INFO",
              "HELD_SOON",
              heldSoon + " held visit" + (heldSoon == 1 ? "" : "s") + " start within 90 minutes",
              "Confirm payment or release the hold so the bay does not idle."));
    }
    return alerts;
  }

  DispatchPriority rankDispatch(DispatchBoardItem item, Instant now) {
    long waitMinutes =
        Math.max(
            0,
            Duration.between(item.incident().createdAt().toInstant(), now).toMinutes());
    int score = (int) Math.min(200, waitMinutes * 2);
    List<String> reasons = new ArrayList<>();
    if (waitMinutes >= 20) {
      reasons.add(waitMinutes + " min waiting");
      score += 15;
    } else if (waitMinutes > 0) {
      reasons.add(waitMinutes + " min waiting");
    }
    if (item.rideHomeRequested()) {
      score += 40;
      reasons.add("ride home requested");
    }
    var status = com.serv60.roadside.api.IncidentStatus.valueOf(item.incident().status());
    switch (status) {
      case OPEN, SEARCHING -> {
        score += 30;
        reasons.add("needs partner");
      }
      case QUOTED, AWAITING_PAYMENT -> {
        score += 20;
        reasons.add(status.name().toLowerCase(Locale.ROOT).replace('_', ' '));
      }
      case ASSIGNED, EN_ROUTE, ON_SCENE, TOWING, CANCELLED -> {
        // A truck is already committed, or the incident is closed: no dispatch urgency.
      }
    }
    if (item.truckLabel() == null || item.truckLabel().isBlank()) {
      score += 25;
      reasons.add("no truck on scene");
    } else {
      reasons.add("truck " + item.truckLabel());
    }

    String suggested = item.truckLabel();
    Integer eta = item.etaMinutes();
    // The board query already resolved the nearest partner; no per-incident fleet scan here.
    if ((suggested == null || suggested.isBlank()) && item.nearestPartnerName() != null) {
      suggested = item.nearestPartnerName();
      eta = item.nearestPartnerEtaMinutes();
      reasons.add("nearest " + suggested + " (~" + eta + "m, "
          + String.format(Locale.ROOT, "%.1f", item.nearestPartnerDistanceKm()) + " km)");
      score += Math.max(0, 30 - eta);
    } else if (eta != null) {
      reasons.add("ETA " + eta + "m");
    }

    if (reasons.isEmpty()) {
      reasons.add("active roadside incident");
    }
    return new DispatchPriority(
        item.incident().id(), score, String.join(" · ", reasons), suggested, eta);
  }
}
