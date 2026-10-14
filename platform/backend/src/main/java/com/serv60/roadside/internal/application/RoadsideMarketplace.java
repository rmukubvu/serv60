package com.serv60.roadside.internal.application;

import com.serv60.errors.Conflict;
import com.serv60.errors.NotFound;
import com.serv60.roadside.api.IncidentStatus;
import com.serv60.roadside.api.RoadsideContracts.*;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Map-first tow quotes, dispatch offers and optional ride-home leg. */
@Service
public class RoadsideMarketplace implements com.serv60.roadside.api.RoadsideDispatch {
  private final JdbcClient db;
  private final Clock clock;
  private final long rideFallbackMinor;

  public RoadsideMarketplace(
      JdbcClient db,
      Clock clock,
      @Value("${serv60.roadside.ride-fallback-minor:8500}") long rideFallbackMinor) {
    this.db = db;
    this.clock = clock;
    this.rideFallbackMinor = rideFallbackMinor;
  }

  public List<NearbyTruck> nearby(double latitude, double longitude) {
    validateCoords(latitude, longitude);
    return db.sql(
            """
            select id, name, latitude, longitude,
              6371.0088 * acos(least(1.0, greatest(-1.0,
                sin(radians(:lat))*sin(radians(latitude)) +
                cos(radians(:lat))*cos(radians(latitude))*cos(radians(longitude-:lon))))) distance_km
            from roadside_partner
            where online=true and capacity>0
            """)
        .param("lat", latitude)
        .param("lon", longitude)
        .query(
            (rs, row) -> {
              double km = rs.getDouble("distance_km");
              int eta = Math.max(5, (int) Math.round(km / 0.5));
              return new NearbyTruck(
                  rs.getObject("id", UUID.class),
                  rs.getString("name"),
                  rs.getDouble("latitude"),
                  rs.getDouble("longitude"),
                  eta,
                  Math.round(km * 10.0) / 10.0);
            })
        .list()
        .stream()
        .filter(t -> t.distanceKm() <= 25)
        .sorted(Comparator.comparingDouble(NearbyTruck::distanceKm))
        .limit(20)
        .toList();
  }

  @Transactional
  public IncidentDetail createGeo(CreateGeoIncident request, String key) {
    if (key == null || key.isBlank()) throw new IllegalArgumentException("Idempotency-Key is required");
    validateCoords(request.latitude(), request.longitude());
    var hash = requestHash(request);
    RoadsideService.lockIdempotencyKey(db, request.customerId(), key);
    var existing =
        db.sql("select id, request_hash from roadside_incident where customer_id=:c and idempotency_key=:k")
            .param("c", request.customerId())
            .param("k", key)
            .query((rs, n) -> Map.entry(rs.getObject("id", UUID.class), String.valueOf(rs.getString("request_hash"))))
            .optional();
    if (existing.isPresent()) {
      // Incidents created before request hashing stored "geo-<id>"; those replay unchecked.
      var stored = existing.get().getValue();
      if (!stored.equals(hash) && !stored.startsWith("geo-"))
        throw new Conflict("Idempotency-Key was reused with a different request");
      return detail(existing.get().getKey());
    }

    double destLat =
        request.destinationLatitude() == null
            ? nearestHubLat(request.latitude(), request.longitude())
            : request.destinationLatitude();
    double destLon =
        request.destinationLongitude() == null
            ? nearestHubLon(request.latitude(), request.longitude())
            : request.destinationLongitude();
    String destLabel =
        request.destinationLabel() == null || request.destinationLabel().isBlank()
            ? "Nearest Serv60 hub"
            : request.destinationLabel();

    UUID id = UUID.randomUUID();
    OffsetDateTime now = OffsetDateTime.now(clock);
    db.sql(
            """
            insert into roadside_incident(id,customer_id,vehicle_id,location,description,status,idempotency_key,
              request_hash,created_at,latitude,longitude,destination_label,destination_latitude,destination_longitude,
              ride_home_requested)
            values (:id,:customer,:vehicle,:location,:description,'QUOTED',:key,:hash,:created,
              :lat,:lon,:destLabel,:destLat,:destLon,:ride)
            """)
        .param("id", id)
        .param("customer", request.customerId())
        .param("vehicle", request.vehicleId())
        .param("location", request.locationLabel())
        .param("description", request.description())
        .param("key", key)
        .param("hash", hash)
        .param("created", now)
        .param("lat", request.latitude())
        .param("lon", request.longitude())
        .param("destLabel", destLabel)
        .param("destLat", destLat)
        .param("destLon", destLon)
        .param("ride", request.rideHomeRequested())
        .update();

    buildQuotes(id, request.latitude(), request.longitude(), destLat, destLon, request.rideHomeRequested());
    if (request.rideHomeRequested()) {
      ensureRidePlaceholder(
          id,
          request.rideDropoffLabel() == null || request.rideDropoffLabel().isBlank()
              ? "Home"
              : request.rideDropoffLabel(),
          request.rideDropoffLatitude(),
          request.rideDropoffLongitude());
    }
    return detail(id);
  }

  @Transactional
  public IncidentDetail acceptQuote(UUID customerId, UUID incidentId, UUID quoteId) {
    var incident = lockOwned(customerId, incidentId);
    // A retried accept of the quote already taken replays the current state instead of failing.
    if (quoteId.equals(incident.selectedQuoteId())) return detail(incidentId);
    if (!IncidentStatus.valueOf(incident.status()).acceptsQuote()) {
      throw new Conflict("This roadside request can no longer accept a quote");
    }
    var quote =
        db.sql(
                "select * from roadside_quote where id=:id and incident_id=:incident for update")
            .param("id", quoteId)
            .param("incident", incidentId)
            .query(this::mapQuote)
            .optional()
            .orElseThrow(() -> new NotFound("Quote not found"));
    if (!quote.expiresAt().isAfter(OffsetDateTime.now(clock))) {
      throw new Conflict("That quote expired. Request a new one");
    }
    requireAvailablePartner(quote.partnerId());
    long total = quote.totalAmountMinor();
    // No roadside payment provider is integrated yet: the accepted amount is recorded as DUE and
    // collected outside the platform. Only a provider event may ever mark it PAID.
    db.sql(
            """
            insert into roadside_payment(id,incident_id,customer_id,amount_minor,currency,status,created_at)
            values (:id,:incident,:customer,:amount,'ZAR','DUE',:created)
            on conflict (incident_id) do update set amount_minor=excluded.amount_minor
              where roadside_payment.status='DUE'
            """)
        .param("id", UUID.randomUUID())
        .param("incident", incidentId)
        .param("customer", customerId)
        .param("amount", total)
        .param("created", OffsetDateTime.now(clock))
        .update();
    db.sql(
            "update roadside_incident set status='SEARCHING', selected_quote_id=:quote,"
                + " dispatch_generation=dispatch_generation+1 where id=:id")
        .param("quote", quoteId)
        .param("id", incidentId)
        .update();
    int generation =
        db.sql("select dispatch_generation from roadside_incident where id=:id")
            .param("id", incidentId)
            .query(Integer.class)
            .single();
    recordAcceptedOffer(incidentId, quote.partnerId(), generation);
    assignPartner(incidentId, quote.partnerId());
    maybeRequestRide(incidentId);
    return detail(incidentId);
  }

  /**
   * Records the customer's ride-home request. No ride provider is integrated, so the leg stays
   * REQUESTED for the dispatcher to arrange; tracking links and PINs are only ever provider data.
   */
  @Transactional
  public IncidentDetail startRide(UUID customerId, UUID incidentId, StartRide request) {
    lockOwned(customerId, incidentId);
    ensureRidePlaceholder(
        incidentId,
        request.dropoffLabel(),
        request.dropoffLatitude(),
        request.dropoffLongitude());
    db.sql("update roadside_ride set status='REQUESTED' where incident_id=:id")
        .param("id", incidentId)
        .update();
    db.sql("update roadside_incident set ride_home_requested=true where id=:id")
        .param("id", incidentId)
        .update();
    return detail(incidentId);
  }

  public IncidentDetail detail(UUID id) {
    var row =
        db.sql("select * from roadside_incident where id=:id")
            .param("id", id)
            .query(this::mapRow)
            .optional()
            .orElseThrow(() -> new NotFound("Roadside incident not found"));
    var quotes =
        db.sql("select * from roadside_quote where incident_id=:id order by amount_minor, eta_minutes")
            .param("id", id)
            .query(this::mapQuote)
            .list();
    var ride =
        db.sql("select * from roadside_ride where incident_id=:id")
            .param("id", id)
            .query(this::mapRide)
            .optional()
            .orElse(null);
    return new IncidentDetail(
        row.id(),
        row.customerId(),
        row.status(),
        row.location(),
        row.description(),
        row.latitude(),
        row.longitude(),
        row.destinationLabel(),
        row.rideHomeRequested(),
        row.truckLabel(),
        row.truckLatitude(),
        row.truckLongitude(),
        row.etaMinutes(),
        quotes,
        ride,
        row.createdAt());
  }

  public List<DispatchBoardItem> dispatchBoard(int limit) {
    if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be between 1 and 100");
    return db.sql(
            """
            select i.*, r.status ride_status from roadside_incident i
              left join roadside_ride r on r.incident_id=i.id
            where i.status in %s
            order by i.created_at asc limit :limit
            """.formatted(IncidentStatus.activeSql()))
        .param("limit", limit)
        .query(
            (rs, row) ->
                new DispatchBoardItem(
                    new Incident(
                        rs.getObject("id", UUID.class),
                        rs.getObject("customer_id", UUID.class),
                        rs.getObject("vehicle_id", UUID.class),
                        rs.getString("status"),
                        rs.getString("location"),
                        rs.getString("description"),
                        rs.getObject("created_at", OffsetDateTime.class)),
                    (Double) rs.getObject("latitude"),
                    (Double) rs.getObject("longitude"),
                    rs.getString("truck_label"),
                    (Double) rs.getObject("truck_latitude"),
                    (Double) rs.getObject("truck_longitude"),
                    (Integer) rs.getObject("eta_minutes"),
                    rs.getString("ride_status"),
                    rs.getBoolean("ride_home_requested")))
        .list();
  }

  private void buildQuotes(
      UUID incidentId,
      double lat,
      double lon,
      double destLat,
      double destLon,
      boolean rideHome) {
    double towKm = haversine(lat, lon, destLat, destLon);
    long rideAmount = rideHome ? rideFallbackMinor : 0;
    OffsetDateTime expires = OffsetDateTime.now(clock).plusMinutes(10);
    for (NearbyTruck truck : nearby(lat, lon)) {
      var partner =
          db.sql("select * from roadside_partner where id=:id")
              .param("id", truck.partnerId())
              .query(
                  (rs, row) ->
                      Map.entry(rs.getLong("callout_minor"), rs.getLong("per_km_minor")))
              .single();
      long tow = partner.getKey() + Math.round(towKm * partner.getValue());
      db.sql(
              """
              insert into roadside_quote(id,incident_id,partner_id,partner_name,amount_minor,ride_amount_minor,
                currency,eta_minutes,distance_km,expires_at)
              values (:id,:incident,:partner,:name,:amount,:ride,'ZAR',:eta,:km,:expires)
              """)
          .param("id", UUID.randomUUID())
          .param("incident", incidentId)
          .param("partner", truck.partnerId())
          .param("name", truck.name())
          .param("amount", tow)
          .param("ride", rideAmount)
          .param("eta", truck.etaMinutes())
          .param("km", towKm)
          .param("expires", expires)
          .update();
    }
  }

  private void requireAvailablePartner(UUID partnerId) {
    boolean available =
        partnerId != null
            && db.sql(
                    "select exists(select 1 from roadside_partner where id=:id and online and"
                        + " capacity>0)")
                .param("id", partnerId)
                .query(Boolean.class)
                .single();
    if (!available) {
      throw new Conflict(
          "That tow partner is no longer available. Choose another quote");
    }
  }

  /** Keeps the dispatch audit trail: which partner was sent, for which generation. */
  private void recordAcceptedOffer(UUID incidentId, UUID partnerId, int generation) {
    db.sql(
            """
            insert into roadside_dispatch_offer(id,incident_id,partner_id,generation,status,expires_at)
            values (:id,:incident,:partner,:gen,'ACCEPTED',:now)
            """)
        .param("id", UUID.randomUUID())
        .param("incident", incidentId)
        .param("partner", partnerId)
        .param("gen", generation)
        .param("now", OffsetDateTime.now(clock))
        .update();
  }

  private void assignPartner(UUID incidentId, UUID partnerId) {
    var partner =
        db.sql("select name, latitude, longitude from roadside_partner where id=:id")
            .param("id", partnerId)
            .query(
                (rs, row) ->
                    Map.of(
                        "name",
                        rs.getString("name"),
                        "lat",
                        rs.getDouble("latitude"),
                        "lon",
                        rs.getDouble("longitude")))
            .single();
    var quote =
        db.sql(
                "select q.* from roadside_quote q join roadside_incident i on i.selected_quote_id=q.id"
                    + " where i.id=:id")
            .param("id", incidentId)
            .query(this::mapQuote)
            .optional();
    db.sql(
            """
            update roadside_incident set status='EN_ROUTE', truck_label=:label,
              truck_latitude=:lat, truck_longitude=:lon, eta_minutes=:eta where id=:id
            """)
        .param("label", partner.get("name"))
        .param("lat", partner.get("lat"))
        .param("lon", partner.get("lon"))
        .param("eta", quote.map(Quote::etaMinutes).orElse(15))
        .param("id", incidentId)
        .update();
    if (quote.isPresent()) {
      db.sql(
              """
              insert into roadside_assignment(id,incident_id,provider,quote_reference,amount_minor,currency,
                estimated_minutes,ride_home,status,created_at)
              values (:id,:incident,:provider,:quote,:amount,'ZAR',:eta,false,'ASSIGNED',:created)
              on conflict (incident_id) do nothing
              """)
          .param("id", UUID.randomUUID())
          .param("incident", incidentId)
          .param("provider", partner.get("name"))
          .param("quote", quote.get().id().toString())
          .param("amount", quote.get().towAmountMinor())
          .param("eta", quote.get().etaMinutes())
          .param("created", OffsetDateTime.now(clock))
          .update();
    }
  }

  private void maybeRequestRide(UUID incidentId) {
    db.sql(
            "update roadside_ride set status='REQUESTED' where incident_id=:id and exists (select 1"
                + " from roadside_incident i where i.id=:id and i.ride_home_requested)")
        .param("id", incidentId)
        .update();
  }

  private void ensureRidePlaceholder(
      UUID incidentId, String label, Double lat, Double lon) {
    db.sql(
            """
            insert into roadside_ride(id,incident_id,provider,status,amount_minor,currency,dropoff_label,
              dropoff_latitude,dropoff_longitude,created_at)
            values (:id,:incident,:provider,'QUOTED',:amount,'ZAR',:label,:lat,:lon,:created)
            on conflict (incident_id) do update set dropoff_label=:label,
              dropoff_latitude=coalesce(:lat, roadside_ride.dropoff_latitude),
              dropoff_longitude=coalesce(:lon, roadside_ride.dropoff_longitude)
            """)
        .param("id", UUID.randomUUID())
        .param("incident", incidentId)
        .param("provider", "LOCAL_PARTNER")
        .param("amount", rideFallbackMinor)
        .param("label", label)
        .param("lat", lat)
        .param("lon", lon)
        .param("created", OffsetDateTime.now(clock))
        .update();
  }

  private IncidentRow lockOwned(UUID customerId, UUID incidentId) {
    return db.sql("select * from roadside_incident where id=:id and customer_id=:customer for update")
        .param("id", incidentId)
        .param("customer", customerId)
        .query(this::mapRow)
        .optional()
        .orElseThrow(() -> new NotFound("Roadside incident not found"));
  }

  private double nearestHubLat(double lat, double lon) {
    return db.sql(
            """
            select latitude from hub where published=true and latitude is not null
            order by 6371.0088 * acos(least(1.0, greatest(-1.0,
              sin(radians(:lat))*sin(radians(latitude)) +
              cos(radians(:lat))*cos(radians(latitude))*cos(radians(longitude-:lon)))))
            limit 1
            """)
        .param("lat", lat)
        .param("lon", lon)
        .query(Double.class)
        .optional()
        .orElse(lat);
  }

  private double nearestHubLon(double lat, double lon) {
    return db.sql(
            """
            select longitude from hub where published=true and longitude is not null
            order by 6371.0088 * acos(least(1.0, greatest(-1.0,
              sin(radians(:lat))*sin(radians(latitude)) +
              cos(radians(:lat))*cos(radians(latitude))*cos(radians(longitude-:lon)))))
            limit 1
            """)
        .param("lat", lat)
        .param("lon", lon)
        .query(Double.class)
        .optional()
        .orElse(lon);
  }

  private static double haversine(double lat1, double lon1, double lat2, double lon2) {
    double dLat = Math.toRadians(lat2 - lat1);
    double dLon = Math.toRadians(lon2 - lon1);
    double a =
        Math.sin(dLat / 2) * Math.sin(dLat / 2)
            + Math.cos(Math.toRadians(lat1))
                * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2)
                * Math.sin(dLon / 2);
    return 6371.0088 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
  }

  private static String requestHash(CreateGeoIncident request) {
    return RoadsideService.sha256(String.join("\u001f",
        "geo", String.valueOf(request.customerId()), String.valueOf(request.vehicleId()),
        String.valueOf(request.latitude()), String.valueOf(request.longitude()),
        request.locationLabel(), request.description(), String.valueOf(request.destinationLabel()),
        String.valueOf(request.destinationLatitude()), String.valueOf(request.destinationLongitude()),
        String.valueOf(request.rideHomeRequested()), String.valueOf(request.rideDropoffLabel()),
        String.valueOf(request.rideDropoffLatitude()), String.valueOf(request.rideDropoffLongitude())));
  }

  private static void validateCoords(double lat, double lon) {
    if (!Double.isFinite(lat)
        || !Double.isFinite(lon)
        || Math.abs(lat) > 90
        || Math.abs(lon) > 180) {
      throw new IllegalArgumentException("Enter a valid location");
    }
  }

  private Quote mapQuote(ResultSet rs, int row) throws SQLException {
    long tow = rs.getLong("amount_minor");
    long ride = rs.getLong("ride_amount_minor");
    return new Quote(
        rs.getObject("id", UUID.class),
        rs.getObject("partner_id", UUID.class),
        rs.getString("partner_name"),
        tow,
        ride,
        tow + ride,
        rs.getString("currency"),
        rs.getInt("eta_minutes"),
        rs.getDouble("distance_km"),
        rs.getObject("expires_at", OffsetDateTime.class));
  }

  private RideLeg mapRide(ResultSet rs, int row) throws SQLException {
    return new RideLeg(
        rs.getObject("id", UUID.class),
        rs.getString("provider"),
        rs.getString("status"),
        rs.getLong("amount_minor"),
        rs.getString("currency"),
        rs.getString("dropoff_label"),
        rs.getString("tracking_url"),
        rs.getString("pin_code"));
  }

  private IncidentRow mapRow(ResultSet rs, int row) throws SQLException {
    return new IncidentRow(
        rs.getObject("id", UUID.class),
        rs.getObject("customer_id", UUID.class),
        rs.getString("status"),
        rs.getString("location"),
        rs.getString("description"),
        (Double) rs.getObject("latitude"),
        (Double) rs.getObject("longitude"),
        rs.getString("destination_label"),
        rs.getBoolean("ride_home_requested"),
        rs.getString("truck_label"),
        (Double) rs.getObject("truck_latitude"),
        (Double) rs.getObject("truck_longitude"),
        (Integer) rs.getObject("eta_minutes"),
        rs.getInt("dispatch_generation"),
        rs.getObject("selected_quote_id", UUID.class),
        rs.getObject("created_at", OffsetDateTime.class));
  }

  private record IncidentRow(
      UUID id,
      UUID customerId,
      String status,
      String location,
      String description,
      Double latitude,
      Double longitude,
      String destinationLabel,
      boolean rideHomeRequested,
      String truckLabel,
      Double truckLatitude,
      Double truckLongitude,
      Integer etaMinutes,
      int generation,
      UUID selectedQuoteId,
      OffsetDateTime createdAt) {}
}
