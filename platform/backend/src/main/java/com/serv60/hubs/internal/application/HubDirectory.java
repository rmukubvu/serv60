package com.serv60.hubs.internal.application;

import com.serv60.errors.NotFound;
import com.serv60.hubs.api.HubContracts.Hub;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/** Published customer hub directory; customer coordinates are never stored. */
@Service
public class HubDirectory implements com.serv60.hubs.api.HubDiscovery {
  private final JdbcClient db;
  private final java.time.Clock clock;

  /**
   * Today's hours from the weekly schedule (the single source the booking paths use), falling back
   * to the legacy daily columns only for a hub whose week was never saved. Null when closed today.
   */
  private static final String TODAY_HOURS =
      """
       case when coalesce(today.closed,false) then null else coalesce(today.opens_at,h.opens_at) end today_opens,
       case when coalesce(today.closed,false) then null else coalesce(today.closes_at,h.closes_at) end today_closes,
      """;
  private static final String TODAY_JOIN =
      " left join hub_trading_day today on today.hub_id=h.id and today.day_of_week="
          + "extract(isodow from cast(:now as timestamptz) at time zone h.timezone)::int ";
  private static final String SERVICES =
      """
      coalesce((select array_agg(distinct st.code order by st.code)
        from service_template st where st.hub_id=h.id and st.active=true
          and st.code in %s and st.duration_minutes<=60
          and exists (select 1 from resource r where r.hub_id=h.id and r.active=true
            and r.kind=%s)), '{}') options
      """
          .formatted(
              com.serv60.hubs.api.ServiceCode.complimentaryCodesSql(),
              com.serv60.hubs.api.ServiceCode.resourceKindSql("st.code"));
  private static final String PUBLISHED =
      """
      h.published=true and exists (select 1 from service_template s
        where s.hub_id=h.id and s.code='EXPRESS_OIL' and s.duration_minutes=60 and s.active=true)
      """;

  public HubDirectory(JdbcClient db, java.time.Clock clock) {
    this.db = db;
    this.clock = clock;
  }

  /** Finds at most 50 published hubs within 20 km, sorted by straight-line distance. */
  public List<Hub> nearby(double latitude, double longitude, String query) {
    if (!Double.isFinite(latitude)
        || !Double.isFinite(longitude)
        || Math.abs(latitude) > 90
        || Math.abs(longitude) > 180) {
      throw new IllegalArgumentException("Enter a valid location");
    }
    if (query == null || query.length() > 80)
      throw new IllegalArgumentException("Search is limited to 80 characters");
    var text =
        "%" + query.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
    return db.sql(
            "select * from (select h.*, " + TODAY_HOURS + " 6371.0088 * acos(least(1.0, greatest(-1.0,"
                + " sin(radians(:lat))*sin(radians(h.latitude)) +"
                + " cos(radians(:lat))*cos(radians(h.latitude))*cos(radians(h.longitude-:lon)))))"
                + " distance_km, "
                + SERVICES
                + " from hub h"
                + TODAY_JOIN
                + " where "
                + PUBLISHED
                + " and (h.name ilike :query or h.address ilike :query)) candidates where"
                + " distance_km<=20 order by distance_km,id limit 50")
        .param("lat", latitude)
        .param("lon", longitude)
        .param("query", text)
        .param("now", java.time.OffsetDateTime.now(clock))
        .query(this::map)
        .list();
  }

  /** Returns a published hub or rejects an unavailable customer destination. */
  public Hub require(UUID id) {
    return db.sql(
            "select h.*, " + TODAY_HOURS + " 0.0 distance_km, "
                + SERVICES
                + " from hub h"
                + TODAY_JOIN
                + " where h.id=:id and "
                + PUBLISHED)
        .param("id", id)
        .param("now", java.time.OffsetDateTime.now(clock))
        .query(this::map)
        .optional()
        .orElseThrow(
            () -> new NotFound("This hub is not available for customer bookings"));
  }

  private Hub map(ResultSet rs, int row) throws SQLException {
    return new Hub(
        rs.getObject("id", UUID.class),
        rs.getString("name"),
        rs.getString("address"),
        rs.getString("timezone"),
        rs.getDouble("latitude"),
        rs.getDouble("longitude"),
        rs.getDouble("distance_km"),
        rs.getObject("today_opens", LocalTime.class),
        rs.getObject("today_closes", LocalTime.class),
        List.copyOf(Arrays.asList((String[]) rs.getArray("options").getArray())));
  }
}
