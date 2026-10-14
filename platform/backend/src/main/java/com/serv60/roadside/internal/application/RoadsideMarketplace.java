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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Map-first tow quotes, dispatch and the optional ride-home leg.
 *
 * <p>No tow partner is integrated, so a customer's accepted quote reserves that partner's capacity
 * and waits in SEARCHING for a dispatcher to phone the partner and confirm the truck. Only that
 * confirmation moves the incident to EN_ROUTE; nothing here claims a truck is coming before then.
 */
@Service
public class RoadsideMarketplace implements com.serv60.roadside.api.RoadsideDispatch {
  private static final Logger log = LoggerFactory.getLogger(RoadsideMarketplace.class);
  static final int QUOTE_TTL_MINUTES = 10;
  /** How long a dispatcher has to confirm an accepted quote before the board flags it. */
  static final int DISPATCH_CONFIRM_MINUTES = 10;

  private final JdbcClient db;
  private final Clock clock;
  private final ObjectMapper json;
  private final long rideFallbackMinor;
  private final double maxTowKm;
  private final int maxActivePerCustomer;

  public RoadsideMarketplace(
      JdbcClient db,
      Clock clock,
      ObjectMapper json,
      @Value("${serv60.roadside.ride-fallback-minor:8500}") long rideFallbackMinor,
      // A metro tow plus the longest regional run the pilot partners accept.
      @Value("${serv60.roadside.max-tow-km:150}") double maxTowKm,
      // Enough for a second vehicle or a retry after a cancel; stops one account flooding a board.
      @Value("${serv60.roadside.max-active-per-customer:3}") int maxActivePerCustomer) {
    if (maxTowKm <= 0) throw new IllegalArgumentException("serv60.roadside.max-tow-km must be positive");
    if (maxActivePerCustomer < 1) {
      throw new IllegalArgumentException("serv60.roadside.max-active-per-customer must be at least 1");
    }
    this.db = db;
    this.clock = clock;
    this.json = json;
    this.rideFallbackMinor = rideFallbackMinor;
    this.maxTowKm = maxTowKm;
    this.maxActivePerCustomer = maxActivePerCustomer;
  }

  public List<NearbyTruck> nearby(double latitude, double longitude) {
    validateCoords(latitude, longitude);
    return db.sql(
            """
            select id, name, latitude, longitude,
              6371.0088 * acos(least(1.0, greatest(-1.0,
                sin(radians(:lat))*sin(radians(latitude)) +
                cos(radians(:lat))*cos(radians(latitude))*cos(radians(longitude-:lon))))) distance_km
            from roadside_partner p
            where online=true and capacity > (%s)
            """.formatted(committedJobsSql("p.id")))
        .param("lat", latitude)
        .param("lon", longitude)
        .query(
            (rs, row) -> {
              double km = rs.getDouble("distance_km");
              int eta = etaMinutes(km);
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
    validateOptionalPoint(request.destinationLatitude(), request.destinationLongitude(), "destination");
    validateOptionalPoint(request.rideDropoffLatitude(), request.rideDropoffLongitude(), "ride drop-off");
    var hash = requestHash(request);
    RoadsideService.lockCustomer(db, request.customerId());
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
    RoadsideService.requireOwnedVehicle(db, request.customerId(), request.vehicleId());
    RoadsideService.requireUnderActiveCap(db, request.customerId(), maxActivePerCustomer);

    var customerDestination =
        request.destinationLatitude() != null && request.destinationLongitude() != null;
    // The incident belongs to the published hub nearest its tow destination (or the breakdown).
    var hub =
        customerDestination
            ? nearestHub(request.destinationLatitude(), request.destinationLongitude())
            : nearestHub(request.latitude(), request.longitude());
    // The default destination is the hub, unless it is beyond tow range: then dispatch agrees one
    // with the customer and the quote is the call-out only.
    var hubInRange =
        hub.filter(h -> haversine(request.latitude(), request.longitude(), h.latitude(), h.longitude())
            <= maxTowKm);
    double destLat =
        customerDestination
            ? request.destinationLatitude()
            : hubInRange.map(NearestHub::latitude).orElse(request.latitude());
    double destLon =
        customerDestination
            ? request.destinationLongitude()
            : hubInRange.map(NearestHub::longitude).orElse(request.longitude());
    if (customerDestination
        && haversine(request.latitude(), request.longitude(), destLat, destLon) > maxTowKm) {
      throw new IllegalArgumentException(
          "That destination is more than " + Math.round(maxTowKm) + " km away. Choose a closer one");
    }
    String destLabel =
        request.destinationLabel() != null && !request.destinationLabel().isBlank()
            ? request.destinationLabel()
            : customerDestination
                ? "Chosen destination"
                : hubInRange.isPresent() ? "Nearest Serv60 hub" : "Destination to be agreed with dispatch";
    if (hub.isEmpty()) {
      log.warn("Roadside incident raised with no published hub; only an ADMIN will see it");
    }

    UUID id = UUID.randomUUID();
    OffsetDateTime now = OffsetDateTime.now(clock);
    db.sql(
            """
            insert into roadside_incident(id,customer_id,vehicle_id,location,description,status,idempotency_key,
              request_hash,created_at,latitude,longitude,destination_label,destination_latitude,destination_longitude,
              ride_home_requested,hub_id)
            values (:id,:customer,:vehicle,:location,:description,'QUOTED',:key,:hash,:created,
              :lat,:lon,:destLabel,:destLat,:destLon,:ride,:hub)
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
        .param("hub", hub.map(NearestHub::id).orElse(null))
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
    reservePartner(quote.partnerId());
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
        .param("amount", quote.towAmountMinor())
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
    recordOffer(incidentId, quote.partnerId(), generation);
    maybeRequestRide(incidentId);
    refreshDue(incidentId);
    emit("RoadsideDispatchRequested", incidentId, Map.of(
        "incidentId", incidentId,
        "partnerId", quote.partnerId(),
        "quoteId", quoteId,
        "generation", generation));
    return detail(incidentId);
  }

  /**
   * A dispatcher has phoned the partner behind the accepted quote and the truck is on its way.
   * Repeating the call after it succeeded replays the current state.
   */
  @Transactional
  public IncidentDetail confirmDispatch(UUID incidentId, int etaMinutes) {
    if (etaMinutes < 1 || etaMinutes > 240) {
      throw new IllegalArgumentException("etaMinutes must be between 1 and 240");
    }
    var incident = lock(incidentId);
    var status = IncidentStatus.valueOf(incident.status());
    if (status == IncidentStatus.EN_ROUTE || status == IncidentStatus.ON_SCENE
        || status == IncidentStatus.TOWING) {
      return detail(incidentId);
    }
    if (status != IncidentStatus.SEARCHING || incident.selectedQuoteId() == null) {
      throw new Conflict("Only a request waiting for its accepted partner can be dispatched");
    }
    var quote =
        db.sql("select * from roadside_quote where id=:id")
            .param("id", incident.selectedQuoteId())
            .query(this::mapQuote)
            .single();
    var partner =
        db.sql("select name, latitude, longitude from roadside_partner where id=:id")
            .param("id", quote.partnerId())
            .query((rs, row) -> new PartnerPosition(
                rs.getString("name"), rs.getDouble("latitude"), rs.getDouble("longitude")))
            .single();
    db.sql(
            """
            update roadside_incident set status='EN_ROUTE', truck_label=:label,
              truck_latitude=:lat, truck_longitude=:lon, eta_minutes=:eta where id=:id
            """)
        .param("label", partner.name())
        .param("lat", partner.latitude())
        .param("lon", partner.longitude())
        .param("eta", etaMinutes)
        .param("id", incidentId)
        .update();
    db.sql(
            "update roadside_dispatch_offer set status='ACCEPTED' where incident_id=:id"
                + " and generation=:gen and status='OFFERED'")
        .param("id", incidentId)
        .param("gen", incident.generation())
        .update();
    db.sql(
            """
            insert into roadside_assignment(id,incident_id,provider,quote_reference,amount_minor,currency,
              estimated_minutes,ride_home,status,created_at)
            values (:id,:incident,:provider,:quote,:amount,'ZAR',:eta,:ride,'ASSIGNED',:created)
            on conflict (incident_id) do nothing
            """)
        .param("id", UUID.randomUUID())
        .param("incident", incidentId)
        .param("provider", partner.name())
        .param("quote", quote.id().toString())
        .param("amount", quote.towAmountMinor())
        .param("eta", etaMinutes)
        .param("ride", incident.rideHomeRequested())
        .param("created", OffsetDateTime.now(clock))
        .update();
    emit("RoadsideDispatchConfirmed", incidentId, Map.of(
        "incidentId", incidentId, "partnerId", quote.partnerId(), "etaMinutes", etaMinutes));
    return detail(incidentId);
  }

  /**
   * Records the truck's progress: EN_ROUTE, ON_SCENE, TOWING, COMPLETED. Repeating the current
   * status is a no-op; skipping back or out of order is refused.
   */
  @Transactional
  public IncidentDetail progress(UUID incidentId, IncidentStatus next) {
    Objects.requireNonNull(next, "status is required");
    var current = IncidentStatus.valueOf(lock(incidentId).status());
    if (current == next) return detail(incidentId);
    if (!current.canProgressTo(next)) {
      throw new Conflict("A " + current + " request cannot move to " + next);
    }
    db.sql("update roadside_incident set status=:status where id=:id")
        .param("status", next.name())
        .param("id", incidentId)
        .update();
    if (next == IncidentStatus.COMPLETED) {
      db.sql("update roadside_assignment set status='COMPLETED' where incident_id=:id")
          .param("id", incidentId)
          .update();
    }
    emit("RoadsideIncidentProgressed", incidentId, Map.of(
        "incidentId", incidentId, "from", current.name(), "status", next.name()));
    return detail(incidentId);
  }

  /**
   * Closes QUOTED requests whose every quote has expired, so stale requests leave the board and
   * stop counting against the customer's limit. Returns how many were closed.
   */
  @Transactional
  public int expireStaleQuotes(OffsetDateTime now, int batchSize) {
    var expired =
        db.sql(
                """
                update roadside_incident set status='EXPIRED' where id in (
                  select i.id from roadside_incident i
                   where i.status='QUOTED' and i.created_at < :cutoff
                     and not exists (select 1 from roadside_quote q
                                      where q.incident_id=i.id and q.expires_at > :now)
                   order by i.created_at limit :batch
                   for update skip locked)
                returning id
                """)
            .param("cutoff", now.minusMinutes(QUOTE_TTL_MINUTES))
            .param("now", now)
            .param("batch", batchSize)
            .query(UUID.class)
            .list();
    for (UUID id : expired) {
      db.sql("update roadside_ride set status='CANCELLED' where incident_id=:id")
          .param("id", id)
          .update();
      emit("RoadsideIncidentExpired", id, Map.of("incidentId", id));
    }
    return expired.size();
  }

  /**
   * Records the customer's ride-home request. No ride provider is integrated, so the leg stays
   * REQUESTED for the dispatcher to arrange; tracking links and PINs are only ever provider data.
   */
  @Transactional
  public IncidentDetail startRide(UUID customerId, UUID incidentId, StartRide request) {
    validateOptionalPoint(request.dropoffLatitude(), request.dropoffLongitude(), "ride drop-off");
    var incident = lockOwned(customerId, incidentId);
    if (IncidentStatus.valueOf(incident.status()).terminal()) {
      throw new Conflict("This roadside request is closed");
    }
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
    refreshDue(incidentId);
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

  /**
   * Active incidents for one hub, oldest first, each with the nearest online partner within 25 km
   * computed in the same query.
   */
  public List<DispatchBoardItem> dispatchBoard(UUID hub, int limit) {
    Objects.requireNonNull(hub, "hub is required");
    return board("i.hub_id=:hub", hub, limit);
  }

  /** Active incidents raised while no hub was published, for an ADMIN to route by hand. */
  public List<DispatchBoardItem> unroutedBoard(int limit) {
    return board("i.hub_id is null", null, limit);
  }

  private List<DispatchBoardItem> board(String hubFilter, UUID hub, int limit) {
    if (limit < 1 || limit > 100) throw new IllegalArgumentException("limit must be between 1 and 100");
    return db.sql(
            """
            select i.*, r.status ride_status, near.name near_name, near.distance_km near_km
              from roadside_incident i
              left join roadside_ride r on r.incident_id=i.id
              left join lateral (
                select p.name, 6371.0088 * acos(least(1.0, greatest(-1.0,
                    sin(radians(i.latitude))*sin(radians(p.latitude)) +
                    cos(radians(i.latitude))*cos(radians(p.latitude))*cos(radians(p.longitude-i.longitude)))))
                  distance_km
                  from roadside_partner p
                 where p.online and p.capacity>0 and i.latitude is not null
                 order by distance_km limit 1) near on near.distance_km <= 25
            where i.status in %s and %s
            order by i.created_at asc limit :limit
            """.formatted(IncidentStatus.activeSql(), hubFilter))
        .param("hub", hub)
        .param("limit", limit)
        .query(
            (rs, row) -> {
              Double nearKm = (Double) rs.getObject("near_km");
              return new DispatchBoardItem(
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
                  rs.getBoolean("ride_home_requested"),
                  rs.getObject("hub_id", UUID.class),
                  rs.getString("near_name"),
                  nearKm == null ? null : etaMinutes(nearKm),
                  nearKm == null ? null : Math.round(nearKm * 10.0) / 10.0);
            })
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
    OffsetDateTime expires = OffsetDateTime.now(clock).plusMinutes(QUOTE_TTL_MINUTES);
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

  /**
   * Holds the partner row while counting its committed jobs, so two customers accepting the last
   * free truck at once cannot both get it. Capacity frees itself when a job completes or cancels.
   */
  private void reservePartner(UUID partnerId) {
    // Lock first, count second: the count must run in a fresh statement so it sees jobs committed
    // by whoever held the lock before us.
    var capacity =
        partnerId == null
            ? Optional.<Integer>empty()
            : db.sql("select case when online then capacity else 0 end from roadside_partner"
                    + " where id=:id for update")
                .param("id", partnerId)
                .query(Integer.class)
                .optional();
    int committed =
        db.sql(committedJobsSql(":id")).param("id", partnerId).query(Integer.class).single();
    if (capacity.orElse(0) <= committed) {
      throw new Conflict("That tow partner is no longer available. Choose another quote");
    }
  }

  /** Jobs a partner's trucks are committed to: accepted and not yet completed or cancelled. */
  private static String committedJobsSql(String partnerColumn) {
    return """
        select count(*) from roadside_incident ci join roadside_quote cq on cq.id=ci.selected_quote_id
         where cq.partner_id=%s and ci.status in %s""".formatted(partnerColumn, IncidentStatus.committedSql());
  }

  /** The DUE amount is the accepted tow plus the ride leg, if one was asked for at any point. */
  private void refreshDue(UUID incidentId) {
    db.sql(
            """
            update roadside_payment p
               set amount_minor = q.amount_minor + coalesce(
                     (select r.amount_minor from roadside_ride r
                       where r.incident_id=p.incident_id and r.status <> 'CANCELLED'), 0)
              from roadside_incident i join roadside_quote q on q.id=i.selected_quote_id
             where p.incident_id=:id and i.id=p.incident_id and p.status='DUE'
            """)
        .param("id", incidentId)
        .update();
  }

  private void emit(String type, UUID aggregate, Map<String, Object> payload) {
    db.sql(
            "insert into outbox_event(id,event_type,aggregate_id,payload)"
                + " values (:id,:type,:aggregate,cast(:payload as jsonb))")
        .param("id", UUID.randomUUID())
        .param("type", type)
        .param("aggregate", aggregate)
        .param("payload", json.writeValueAsString(payload))
        .update();
  }

  /**
   * Keeps the dispatch audit trail: which partner the customer chose, for which generation. The
   * offer stays OFFERED until a dispatcher confirms the truck; {@code expires_at} is when the board
   * should treat the confirmation as overdue.
   */
  private void recordOffer(UUID incidentId, UUID partnerId, int generation) {
    db.sql(
            """
            insert into roadside_dispatch_offer(id,incident_id,partner_id,generation,status,expires_at)
            values (:id,:incident,:partner,:gen,'OFFERED',:due)
            """)
        .param("id", UUID.randomUUID())
        .param("incident", incidentId)
        .param("partner", partnerId)
        .param("gen", generation)
        .param("due", OffsetDateTime.now(clock).plusMinutes(DISPATCH_CONFIRM_MINUTES))
        .update();
  }

  private record PartnerPosition(String name, double latitude, double longitude) {}

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

  private IncidentRow lock(UUID incidentId) {
    return db.sql("select * from roadside_incident where id=:id for update")
        .param("id", incidentId)
        .query(this::mapRow)
        .optional()
        .orElseThrow(() -> new NotFound("Roadside incident not found"));
  }

  private IncidentRow lockOwned(UUID customerId, UUID incidentId) {
    return db.sql("select * from roadside_incident where id=:id and customer_id=:customer for update")
        .param("id", incidentId)
        .param("customer", customerId)
        .query(this::mapRow)
        .optional()
        .orElseThrow(() -> new NotFound("Roadside incident not found"));
  }

  private Optional<NearestHub> nearestHub(double lat, double lon) {
    return db.sql(
            """
            select id, latitude, longitude from hub where published=true and latitude is not null
            order by 6371.0088 * acos(least(1.0, greatest(-1.0,
              sin(radians(:lat))*sin(radians(latitude)) +
              cos(radians(:lat))*cos(radians(latitude))*cos(radians(longitude-:lon)))))
            limit 1
            """)
        .param("lat", lat)
        .param("lon", lon)
        .query(
            (rs, row) ->
                new NearestHub(
                    rs.getObject("id", UUID.class),
                    rs.getDouble("latitude"),
                    rs.getDouble("longitude")))
        .optional();
  }

  private record NearestHub(UUID id, double latitude, double longitude) {}

  /** Rough urban tow ETA: 30 km/h, never under five minutes. */
  private static int etaMinutes(double km) {
    return Math.max(5, (int) Math.round(km / 0.5));
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

  /** An optional point must have both coordinates or neither, and both must be valid. */
  private static void validateOptionalPoint(Double lat, Double lon, String what) {
    if (lat == null && lon == null) return;
    if (lat == null || lon == null) {
      throw new IllegalArgumentException("Give both coordinates for the " + what + ", or neither");
    }
    validateCoords(lat, lon);
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
