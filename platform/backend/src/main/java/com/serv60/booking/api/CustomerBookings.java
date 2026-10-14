package com.serv60.booking.api;

import com.serv60.booking.api.CustomerBookingContracts.*;
import java.time.*;
import java.util.*;

/** Customer booking boundary used by conversational and web adapters. */
public interface CustomerBookings {
  Appointments upcoming(UUID customer, int limit, OffsetDateTime after, UUID afterId);

  Appointment require(UUID customer, UUID id);

  List<BookingContracts.Availability> slots(
      UUID customer, UUID hub, LocalDate date, String option, UUID replacing);

  BookingContracts.Hold create(UUID customer, NewVisit visit, String key);

  Appointment change(UUID customer, UUID id, ChangeVisit change, String key);

  Appointment cancel(UUID customer, UUID id);
}
