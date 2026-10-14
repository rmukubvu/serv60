package com.serv60.roadside.api;

import java.util.List;
import java.util.UUID;

/** Read access to one hub's live roadside incidents, for operator dashboards. */
public interface RoadsideDispatch {
  /**
   * Returns the hub's active incidents oldest first, at most {@code limit} rows (1 to 100), each
   * with the nearest online partner already resolved. Incidents with no hub are not included.
   */
  List<RoadsideContracts.DispatchBoardItem> dispatchBoard(UUID hub, int limit);
}
