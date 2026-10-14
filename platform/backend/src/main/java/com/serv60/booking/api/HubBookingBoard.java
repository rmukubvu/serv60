package com.serv60.booking.api;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Read-only operator view of a hub's allocations and parts, for other modules' dashboards. */
public interface HubBookingBoard {
  /** Returns active allocations overlapping the range, which may not exceed 31 days. */
  List<BookingContracts.CalendarEntry> calendar(UUID hub, OffsetDateTime from, OffsetDateTime to);

  /** Returns stock and pending demand per SKU, at most {@code limit} rows (1 to 200). */
  List<BookingContracts.PartsReadiness> partsReadiness(UUID hub, int limit);
}
