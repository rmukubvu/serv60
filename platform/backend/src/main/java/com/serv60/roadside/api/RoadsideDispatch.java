package com.serv60.roadside.api;

import java.util.List;

/** Read access to live roadside incidents and the partner fleet, for operator dashboards. */
public interface RoadsideDispatch {
  /** Returns active incidents oldest first, at most {@code limit} rows (1 to 100). */
  List<RoadsideContracts.DispatchBoardItem> dispatchBoard(int limit);

  /** Returns online partners within 25 km of the point, nearest first, at most 20. */
  List<RoadsideContracts.NearbyTruck> nearby(double latitude, double longitude);
}
