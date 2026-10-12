package com.serv60.hubs.internal.application;

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
  private static final String SERVICES =
      """
      coalesce((select array_agg(distinct st.code order by st.code)
        from service_template st where st.hub_id=h.id and st.active=true
          and st.code in ('BARBER','MASSAGE','PRIVATE_WORK') and st.duration_minutes<=60
          and exists (select 1 from resource r where r.hub_id=h.id and r.active=true
            and r.kind=case st.code when 'BARBER' then 'BARBER_CHAIR'
              when 'MASSAGE' then 'MASSAGE_ROOM' else 'WORKSPACE' end)), '{}') options
      """;
  private static final String PUBLISHED =
      """
      h.published=true and exists (select 1 from service_template s
        where s.hub_id=h.id and s.code='EXPRESS_OIL' and s.duration_minutes=60 and s.active=true)
      """;

  public HubDirectory(JdbcClient db) {
    this.db = db;
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
            "select * from (select h.*, 6371.0088 * acos(least(1.0, greatest(-1.0,"
                + " sin(radians(:lat))*sin(radians(h.latitude)) +"
                + " cos(radians(:lat))*cos(radians(h.latitude))*cos(radians(h.longitude-:lon)))))"
                + " distance_km, "
                + SERVICES
                + " from hub h where "
                + PUBLISHED
                + " and (h.name ilike :query or h.address ilike :query)) candidates where"
                + " distance_km<=20 order by distance_km,id limit 50")
        .param("lat", latitude)
        .param("lon", longitude)
        .param("query", text)
        .query(this::map)
        .list();
  }

  /** Returns a published hub or rejects an unavailable customer destination. */
  public Hub require(UUID id) {
    return db.sql(
            "select h.*, 0.0 distance_km, "
                + SERVICES
                + " from hub h where h.id=:id and "
                + PUBLISHED)
        .param("id", id)
        .query(this::map)
        .optional()
        .orElseThrow(
            () -> new IllegalArgumentException("This hub is not available for customer bookings"));
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
        rs.getObject("opens_at", LocalTime.class),
        rs.getObject("closes_at", LocalTime.class),
        List.copyOf(Arrays.asList((String[]) rs.getArray("options").getArray())));
  }
}
