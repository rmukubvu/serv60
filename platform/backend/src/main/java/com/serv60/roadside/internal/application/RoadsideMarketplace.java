package com.serv60.roadside.internal.application;

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
public class RoadsideMarketplace {
  private final JdbcClient db;
  private final Clock clock;
  private final long rideFallbackMinor;
  private final boolean uberConfigured;

  public RoadsideMarketplace(
      JdbcClient db,
      Clock clock,
      @Value("${serv60.roadside.ride-fallback-minor:8500}") long rideFallbackMinor,
      @Value("${serv60.roadside.uber-guest-rides-enabled:false}") boolean uberConfigured) {
    this.db = db;
    this.clock = clock;
    this.rideFallbackMinor = rideFallbackMinor;
    this.uberConfigured = uberConfigured;
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
    var existing =
        db.sql("select id from roadside_incident where customer_id=:c and idempotency_key=:k")
            .param("c", request.customerId())
            .param("k", key)
            .query(UUID.class)
            .optional();
    if (existing.isPresent()) return detail(existing.get());

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
        .param("hash", "geo-" + id)
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
    if (!List.of("QUOTED", "AWAITING_PAYMENT").contains(incident.status())) {
      throw new IllegalStateException("This roadside request can no longer accept a quote");
    }
    var quote =
        db.sql(
                "select * from roadside_quote where id=:id and incident_id=:incident for update")
            .param("id", quoteId)
            .param("incident", incidentId)
            .query(this::mapQuote)
            .optional()
            .orElseThrow(() -> new IllegalArgumentException("Quote not found"));
    if (!quote.expiresAt().isAfter(OffsetDateTime.now(clock))) {
      throw new IllegalStateException("That quote expired. Request a new one");
    }
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
    createOffers(incidentId, generation, incident.latitude(), incident.longitude());
    autoAssignNearest(incidentId, generation);
    maybeActivateRide(incidentId);
    return detail(incidentId);
  }

  @Transactional
  public IncidentDetail partnerAccept(UUID partnerId, UUID offerId) {
    var offer =
        db.sql("select * from roadside_dispatch_offer where id=:id for update")
            .param("id", offerId)
            .query(
                (rs, row) ->
                    Map.of(
                        "id", rs.getObject("id", UUID.class),
                        "incident", rs.getObject("incident_id", UUID.class),
                        "partner", rs.getObject("partner_id", UUID.class),
                        "status", rs.getString("status"),
                        "expires", rs.getObject("expires_at", OffsetDateTime.class),
                        "generation", rs.getInt("generation")))
            .optional()
            .orElseThrow(() -> new IllegalArgumentException("Offer not found"));
    if (!partnerId.equals(offer.get("partner"))) {
      throw new IllegalArgumentException("Offer does not belong to this partner");
    }
    if (!"OPEN".equals(offer.get("status"))) {
      throw new IllegalStateException("Offer is no longer open");
    }
    if (!((OffsetDateTime) offer.get("expires")).isAfter(OffsetDateTime.now(clock))) {
      throw new IllegalStateException("Offer expired");
    }
    UUID incidentId = (UUID) offer.get("incident");
    var incident =
        db.sql("select * from roadside_incident where id=:id for update")
            .param("id", incidentId)
            .query(this::mapRow)
            .single();
    if (!"SEARCHING".equals(incident.status())) {
      throw new IllegalStateException("Incident is no longer searching for a truck");
    }
    if (incident.generation() != (Integer) offer.get("generation")) {
      throw new IllegalStateException("Stale dispatch generation");
    }
    assignPartner(incidentId, partnerId, "PARTNER");
    db.sql("update roadside_dispatch_offer set status='ACCEPTED' where id=:id")
        .param("id", offerId)
        .update();
    db.sql(
            "update roadside_dispatch_offer set status='EXPIRED' where incident_id=:id and"
                + " status='OPEN'")
        .param("id", incidentId)
        .update();
    return detail(incidentId);
  }

  @Transactional
  public IncidentDetail heartbeat(UUID incidentId, PartnerHeartbeat body) {
    validateCoords(body.latitude(), body.longitude());
    db.sql(
            "update roadside_incident set truck_latitude=:lat, truck_longitude=:lon,"
                + " eta_minutes=coalesce(:eta, eta_minutes) where id=:id and status in"
                + " ('ASSIGNED','EN_ROUTE','ON_SCENE','TOWING')")
        .param("lat", body.latitude())
        .param("lon", body.longitude())
        .param("eta", body.etaMinutes())
        .param("id", incidentId)
        .update();
    return detail(incidentId);
  }

  @Transactional
  public IncidentDetail startRide(UUID customerId, UUID incidentId, StartRide request) {
    lockOwned(customerId, incidentId);
    ensureRidePlaceholder(
        incidentId,
        request.dropoffLabel(),
        request.dropoffLatitude(),
        request.dropoffLongitude());
    String provider = uberConfigured ? "UBER_GUEST" : "LOCAL_PARTNER";
    String tracking =
        uberConfigured
            ? "https://trip.uber.com/guest-demo"
            : null;
    db.sql(
            """
            update roadside_ride set provider=:provider, status='BOOKED', tracking_url=:url,
              pin_code=coalesce(pin_code,'4821'), dropoff_label=:label,
              dropoff_latitude=:lat, dropoff_longitude=:lon
            where incident_id=:id
            """)
        .param("provider", provider)
        .param("url", tracking)
        .param("label", request.dropoffLabel())
        .param("lat", request.dropoffLatitude())
        .param("lon", request.dropoffLongitude())
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
            .orElseThrow(() -> new IllegalArgumentException("Roadside incident not found"));
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
            where i.status in ('OPEN','QUOTED','AWAITING_PAYMENT','SEARCHING','ASSIGNED','EN_ROUTE','ON_SCENE','TOWING')
            order by i.created_at asc limit :limit
            """)
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

  private void createOffers(UUID incidentId, int generation, Double lat, Double lon) {
    if (lat == null || lon == null) return;
    OffsetDateTime expires = OffsetDateTime.now(clock).plusMinutes(3);
    for (NearbyTruck truck : nearby(lat, lon).stream().limit(5).toList()) {
      db.sql(
              """
              insert into roadside_dispatch_offer(id,incident_id,partner_id,generation,status,expires_at)
              values (:id,:incident,:partner,:gen,'OPEN',:expires)
              """)
          .param("id", UUID.randomUUID())
          .param("incident", incidentId)
          .param("partner", truck.partnerId())
          .param("gen", generation)
          .param("expires", expires)
          .update();
    }
  }

  private void autoAssignNearest(UUID incidentId, int generation) {
    var offer =
        db.sql(
                """
                select o.id, o.partner_id from roadside_dispatch_offer o
                  join roadside_partner p on p.id=o.partner_id
                where o.incident_id=:id and o.generation=:gen and o.status='OPEN'
                order by p.updated_at desc limit 1
                """)
            .param("id", incidentId)
            .param("gen", generation)
            .query(
                (rs, row) ->
                    Map.entry(rs.getObject("id", UUID.class), rs.getObject("partner_id", UUID.class)))
            .optional();
    if (offer.isEmpty()) return;
    assignPartner(incidentId, offer.get().getValue(), "AUTO");
    db.sql("update roadside_dispatch_offer set status='ACCEPTED' where id=:id")
        .param("id", offer.get().getKey())
        .update();
    db.sql(
            "update roadside_dispatch_offer set status='EXPIRED' where incident_id=:id and"
                + " status='OPEN'")
        .param("id", incidentId)
        .update();
  }

  private void assignPartner(UUID incidentId, UUID partnerId, String mode) {
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
        .param("label", partner.get("name") + " · " + mode)
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

  private void maybeActivateRide(UUID incidentId) {
    boolean requested =
        db.sql("select ride_home_requested from roadside_incident where id=:id")
            .param("id", incidentId)
            .query(Boolean.class)
            .single();
    if (!requested) return;
    String provider = uberConfigured ? "UBER_GUEST" : "LOCAL_PARTNER";
    String tracking = uberConfigured ? "https://trip.uber.com/guest-demo" : null;
    db.sql(
            """
            update roadside_ride set provider=:provider, status='BOOKED', tracking_url=:url,
              pin_code=coalesce(pin_code,'4821')
            where incident_id=:id
            """)
        .param("provider", provider)
        .param("url", tracking)
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
        .param("provider", uberConfigured ? "UBER_GUEST" : "LOCAL_PARTNER")
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
        .orElseThrow(() -> new IllegalArgumentException("Roadside incident not found"));
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
