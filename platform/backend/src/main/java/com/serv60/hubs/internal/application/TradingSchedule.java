package com.serv60.hubs.internal.application;

import com.serv60.hubs.api.HubSchedule;
import com.serv60.hubs.api.HubSettingsContracts.TradingDay;
import java.time.*;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TradingSchedule implements HubSchedule {
  private final JdbcClient db;

  public TradingSchedule(JdbcClient db) {
    this.db = db;
  }

  /** Reads one day's hours, falling back to legacy daily hours for newly provisioned hubs. */
  public TradingDay day(UUID hub, LocalDate date) {
    return db.sql(
            """
            select coalesce(d.closed,false) closed, coalesce(d.opens_at,h.opens_at) opens_at,
              coalesce(d.closes_at,h.closes_at) closes_at from hub h
              left join hub_trading_day d on d.hub_id=h.id and d.day_of_week=:day where h.id=:hub
            """)
        .param("hub", hub)
        .param("day", date.getDayOfWeek().getValue())
        .query(
            (rs, n) ->
                new TradingDay(
                    date.getDayOfWeek().getValue(),
                    rs.getBoolean("closed"),
                    rs.getObject("opens_at", LocalTime.class),
                    rs.getObject("closes_at", LocalTime.class)))
        .optional()
        .orElseThrow(() -> new IllegalArgumentException("Hub not found"));
  }

  /**
   * Shares the hub lock until allocation commits so settings cannot invalidate an in-flight
   * booking.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void requireOpen(UUID hub, OffsetDateTime start, OffsetDateTime end) {
    var zone =
        ZoneId.of(
            db.sql("select timezone from hub where id=:hub for share")
                .param("hub", hub)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new IllegalArgumentException("Hub not found")));
    var localStart = start.atZoneSameInstant(zone);
    var localEnd = end.atZoneSameInstant(zone);
    var hours = day(hub, localStart.toLocalDate());
    if (hours.closed()
        || !end.isAfter(start)
        || !localStart.toLocalDate().equals(localEnd.toLocalDate())
        || localStart.toLocalTime().isBefore(hours.opensAt())
        || localEnd.toLocalTime().isAfter(hours.closesAt()))
      throw new IllegalStateException(
          "This time is outside the hub's trading hours. Check availability again");
  }
}
