package com.serv60.booking.internal.application;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class HoldExpiryJob {
  private final BookingService bookings;

  public HoldExpiryJob(BookingService bookings) { this.bookings = bookings; }

  @Scheduled(fixedDelayString = "${serv60.booking.expiry-sweep-ms:30000}")
  public void expireDueHolds() { bookings.expireDueHolds(OffsetDateTime.now(ZoneOffset.UTC)); }
}
