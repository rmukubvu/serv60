package com.serv60.roadside.internal.application;

import static org.assertj.core.api.Assertions.*;

import com.serv60.roadside.api.IncidentStatus;
import com.serv60.roadside.api.RoadsideContracts.*;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.*;
import org.springframework.transaction.annotation.Transactional;
import com.serv60.support.PlatformIntegrationTest;

@PlatformIntegrationTest
@Transactional
class RoadsideMarketplaceIntegrationTest {
  // Johannesburg, far from any seeded partner, so every quote comes from this test's fleet.
  static final double LAT = -26.2041;
  static final double LON = 28.0473;

  @Autowired RoadsideMarketplace marketplace;
  @Autowired JdbcClient db;
  final UUID customer = UUID.randomUUID();

  UUID partner(String name, double latOffset) {
    var id = UUID.randomUUID();
    db.sql(
            "insert into roadside_partner(id,name,latitude,longitude,online,callout_minor,"
                + "per_km_minor,capacity) values (:id,:name,:lat,:lon,true,40000,1500,1)")
        .param("id", id)
        .param("name", name)
        .param("lat", LAT + latOffset)
        .param("lon", LON)
        .update();
    return id;
  }

  CreateGeoIncident request(boolean rideHome) {
    return request(customer, rideHome);
  }

  CreateGeoIncident request(UUID who, boolean rideHome) {
    return new CreateGeoIncident(
        who, null, LAT, LON, "M1 north", "Flat tyre", "Workshop", LAT + 0.05, LON,
        rideHome, rideHome ? "Home" : null, null, null);
  }

  String paymentStatus(UUID incident) {
    return db.sql("select status from roadside_payment where incident_id=:id")
        .param("id", incident).query(String.class).single();
  }

  long dueAmount(UUID incident) {
    return db.sql("select amount_minor from roadside_payment where incident_id=:id")
        .param("id", incident).query(Long.class).single();
  }

  IncidentDetail accepted(UUID partner, String key) {
    var incident = marketplace.createGeo(request(false), key);
    return marketplace.acceptQuote(customer, incident.id(), quoteFrom(incident, partner).id());
  }

  Quote quoteFrom(IncidentDetail incident, UUID partner) {
    return incident.quotes().stream()
        .filter(q -> partner.equals(q.partnerId()))
        .findFirst()
        .orElseThrow();
  }

  @Test
  void shouldDispatchThePartnerWhoseQuoteTheCustomerAccepted() {
    var quoted = partner("Quoted Tow", 0.02);
    var fresher = partner("Fresher Tow", 0.005);
    db.sql("update roadside_partner set updated_at=now()+interval '1 hour' where id=:id")
        .param("id", fresher)
        .update();
    var incident = marketplace.createGeo(request(false), "geo-dispatch");

    var accepted = marketplace.acceptQuote(customer, incident.id(), quoteFrom(incident, quoted).id());
    var dispatched = marketplace.confirmDispatch(incident.id(), 12);

    assertThat(accepted.status()).isEqualTo("SEARCHING");
    assertThat(accepted.truckLabel()).isNull();
    assertThat(dispatched.status()).isEqualTo("EN_ROUTE");
    assertThat(dispatched.truckLabel()).isEqualTo("Quoted Tow");
    assertThat(dispatched.etaMinutes()).isEqualTo(12);
    assertThat(
            db.sql("select status from roadside_dispatch_offer where incident_id=:id")
                .param("id", incident.id())
                .query(String.class)
                .single())
        .isEqualTo("ACCEPTED");
    assertThat(
            db.sql("select provider from roadside_assignment where incident_id=:id")
                .param("id", incident.id())
                .query(String.class)
                .single())
        .isEqualTo("Quoted Tow");
  }

  @Test
  void shouldRefuseAQuoteFromAPartnerThatWentOffline() {
    var leaving = partner("Leaving Tow", 0.01);
    var incident = marketplace.createGeo(request(false), "geo-offline");
    var quote = quoteFrom(incident, leaving);
    db.sql("update roadside_partner set online=false where id=:id").param("id", leaving).update();

    assertThatThrownBy(() -> marketplace.acceptQuote(customer, incident.id(), quote.id()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no longer available");
    assertThat(
            db.sql("select count(*) from roadside_payment where incident_id=:id")
                .param("id", incident.id())
                .query(Integer.class)
                .single())
        .isZero();
  }

  @Test
  void shouldRequestTheRideHomeWithoutInventingATrackingLinkOrPin() {
    var tow = partner("Ride Tow", 0.01);
    var hub = publishedHub(0.05);
    var incident = marketplace.createGeo(request(true), "geo-ride");
    assertThat(incident.ride().status()).isEqualTo("QUOTED");

    var accepted = marketplace.acceptQuote(customer, incident.id(), quoteFrom(incident, tow).id());
    var moved =
        marketplace.startRide(customer, incident.id(), new StartRide("Office", LAT + 0.1, LON));

    assertThat(accepted.ride())
        .extracting(RideLeg::status, RideLeg::trackingUrl, RideLeg::pinCode)
        .containsExactly("REQUESTED", null, null);
    assertThat(moved.ride().dropoffLabel()).isEqualTo("Office");
    assertThat(marketplace.dispatchBoard(hub, 100))
        .anySatisfy(item -> assertThat(item.incident().id()).isEqualTo(incident.id()));
  }

  UUID publishedHub(double latOffset) {
    var hub = UUID.randomUUID();
    db.sql(
            "insert into hub(id,name,timezone,latitude,longitude,address,published) values"
                + " (:id,'Joburg hub','Africa/Johannesburg',:lat,:lon,'Test',true)")
        .param("id", hub)
        .param("lat", LAT + latOffset)
        .param("lon", LON)
        .update();
    return hub;
  }

  @Test
  void shouldShowAnIncidentOnlyToTheHubItIsTowedToWithItsNearestPartner() {
    var home = publishedHub(0.05);
    var elsewhere = publishedHub(5.0);
    partner("Board Tow", 0.01);

    var incident = marketplace.createGeo(request(false), "geo-board");

    assertThat(marketplace.dispatchBoard(home, 100))
        .filteredOn(item -> item.incident().id().equals(incident.id()))
        .singleElement()
        .satisfies(item -> {
          assertThat(item.hubId()).isEqualTo(home);
          assertThat(item.nearestPartnerName()).isEqualTo("Board Tow");
          assertThat(item.nearestPartnerEtaMinutes()).isEqualTo(5);
        });
    assertThat(marketplace.dispatchBoard(elsewhere, 100))
        .noneMatch(item -> item.incident().id().equals(incident.id()));
  }

  @Autowired RoadsideService roadside;

  @Test
  void shouldVoidAnUncollectedAmountWhenTheRequestIsCancelled() {
    partner("Void Tow", 0.01);
    var incident = marketplace.createGeo(request(false), "geo-void");
    db.sql(
            "insert into roadside_payment(id,incident_id,customer_id,amount_minor,currency,status)"
                + " values (:id,:incident,:customer,50000,'ZAR','DUE')")
        .param("id", UUID.randomUUID())
        .param("incident", incident.id())
        .param("customer", customer)
        .update();

    roadside.cancel(incident.id(), false);

    assertThat(paymentStatus(incident.id())).isEqualTo("VOID");
  }

  @Test
  void shouldAskDispatchToConfirmAndEmitTheRequestWhenAQuoteIsAccepted() {
    var tow = partner("Asked Tow", 0.01);

    var incident = accepted(tow, "geo-asked");

    assertThat(
            db.sql("select count(*) from outbox_event where aggregate_id=:id and event_type='RoadsideDispatchRequested'")
                .param("id", incident.id())
                .query(Integer.class)
                .single())
        .isEqualTo(1);
    assertThat(marketplace.confirmDispatch(incident.id(), 9).status()).isEqualTo("EN_ROUTE");
    assertThat(marketplace.confirmDispatch(incident.id(), 30).etaMinutes()).isEqualTo(9);
  }

  @Test
  void shouldRefuseTheLastTruckToASecondCustomerAndFreeItOnCancel() {
    var tow = partner("Busy Tow", 0.01);
    var other = UUID.randomUUID();
    var second = marketplace.createGeo(request(other, false), "geo-busy-2");
    var first = accepted(tow, "geo-busy-1");

    assertThatThrownBy(() -> marketplace.acceptQuote(other, second.id(), quoteFrom(second, tow).id()))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no longer available");

    roadside.cancel(first.id(), false);
    assertThat(marketplace.acceptQuote(other, second.id(), quoteFrom(second, tow).id()).status())
        .isEqualTo("SEARCHING");
  }

  @Test
  void shouldNotQuoteAPartnerWhoseTrucksAreAllCommitted() {
    var tow = partner("Committed Tow", 0.01);
    accepted(tow, "geo-committed");

    var next = marketplace.createGeo(request(UUID.randomUUID(), false), "geo-after-committed");

    assertThat(next.quotes()).noneMatch(q -> tow.equals(q.partnerId()));
  }

  @Test
  void shouldRecordProgressInOrderAndCloseTheIncident() {
    var tow = partner("Progress Tow", 0.01);
    var hub = publishedHub(0.05);
    var incident = accepted(tow, "geo-progress");
    marketplace.confirmDispatch(incident.id(), 10);

    assertThatThrownBy(() -> marketplace.progress(incident.id(), IncidentStatus.COMPLETED))
        .isInstanceOf(IllegalStateException.class);
    marketplace.progress(incident.id(), IncidentStatus.ON_SCENE);
    marketplace.progress(incident.id(), IncidentStatus.ON_SCENE);
    marketplace.progress(incident.id(), IncidentStatus.TOWING);
    var done = marketplace.progress(incident.id(), IncidentStatus.COMPLETED);

    assertThat(done.status()).isEqualTo("COMPLETED");
    assertThat(marketplace.dispatchBoard(hub, 100)).noneMatch(item -> item.incident().id().equals(incident.id()));
    assertThat(
            db.sql("select status from roadside_assignment where incident_id=:id")
                .param("id", incident.id())
                .query(String.class)
                .single())
        .isEqualTo("COMPLETED");
  }

  @Test
  void shouldRefuseToDispatchARequestNobodyAccepted() {
    partner("Idle Tow", 0.01);
    var incident = marketplace.createGeo(request(false), "geo-not-accepted");

    assertThatThrownBy(() -> marketplace.confirmDispatch(incident.id(), 10))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> marketplace.confirmDispatch(incident.id(), 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void shouldLetOnlyTheHubCancelOnceTheTruckIsConfirmedAndVoidTheAmount() {
    var tow = partner("Called Off Tow", 0.01);
    var incident = accepted(tow, "geo-called-off");
    marketplace.confirmDispatch(incident.id(), 10);

    assertThatThrownBy(() -> roadside.cancel(incident.id(), false))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Call the hub");
    roadside.cancel(incident.id(), true);

    assertThat(paymentStatus(incident.id())).isEqualTo("VOID");
    assertThatThrownBy(() -> marketplace.progress(incident.id(), IncidentStatus.ON_SCENE))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void shouldAddTheRideLegToTheAmountDueOnceWhenAskedAfterAccepting() {
    var tow = partner("Late Ride Tow", 0.01);
    var incident = accepted(tow, "geo-late-ride");
    long tow0 = dueAmount(incident.id());

    marketplace.startRide(customer, incident.id(), new StartRide("Home", null, null));
    marketplace.startRide(customer, incident.id(), new StartRide("Office", null, null));

    assertThat(dueAmount(incident.id())).isEqualTo(tow0 + 8500);
  }

  @Test
  void shouldRefuseARideForAClosedRequest() {
    partner("Closed Ride Tow", 0.01);
    var incident = marketplace.createGeo(request(false), "geo-closed-ride");
    roadside.cancel(incident.id(), false);

    assertThatThrownBy(() -> marketplace.startRide(customer, incident.id(), new StartRide("Home", null, null)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("closed");
  }

  @Test
  void shouldExpireARequestOnceEveryQuoteHasLapsed() {
    partner("Lapsed Tow", 0.01);
    var incident = marketplace.createGeo(request(true), "geo-lapsed");

    marketplace.expireStaleQuotes(incident.createdAt().plusMinutes(5), 1000);
    assertThat(marketplace.detail(incident.id()).status()).isEqualTo("QUOTED");
    marketplace.expireStaleQuotes(incident.createdAt().plusMinutes(11), 1000);

    var expired = marketplace.detail(incident.id());
    assertThat(expired.status()).isEqualTo("EXPIRED");
    assertThat(expired.ride().status()).isEqualTo("CANCELLED");
    assertThatThrownBy(() -> roadside.cancel(incident.id(), false)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void shouldCapOpenRequestsPerCustomer() {
    partner("Cap Tow", 0.01);
    for (int i = 0; i < 3; i++) marketplace.createGeo(request(false), "geo-cap-" + i);

    assertThatThrownBy(() -> marketplace.createGeo(request(false), "geo-cap-3"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("3 open roadside requests");
    assertThat(marketplace.createGeo(request(false), "geo-cap-0").id()).isNotNull();
  }

  @Test
  void shouldRejectHalfADestinationOrOneBeyondTowRange() {
    var half = new CreateGeoIncident(customer, null, LAT, LON, "M1", "Flat", null, LAT + 0.1, null,
        false, null, null, null);
    var far = new CreateGeoIncident(customer, null, LAT, LON, "M1", "Flat", "Cape Town", -33.92, 18.42,
        false, null, null, null);

    assertThatThrownBy(() -> marketplace.createGeo(half, "geo-half"))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("both coordinates");
    assertThatThrownBy(() -> marketplace.createGeo(far, "geo-far"))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("150 km");
  }

  @Test
  void shouldRejectAVehicleThatIsNotTheCustomers() {
    var stranger = new CreateGeoIncident(customer, UUID.randomUUID(), LAT, LON, "M1", "Flat", null, null,
        null, false, null, null, null);

    assertThatThrownBy(() -> marketplace.createGeo(stranger, "geo-vehicle"))
        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Vehicle");
  }

  @Test
  void shouldKeepIncidentsWithNoHubOffHubBoardsAndOnTheUnroutedBoard() {
    var hub = publishedHub(0.05);
    var incident = marketplace.createGeo(request(false), "geo-unrouted");
    db.sql("update roadside_incident set hub_id=null where id=:id").param("id", incident.id()).update();

    assertThat(marketplace.dispatchBoard(hub, 100)).noneMatch(item -> item.incident().id().equals(incident.id()));
    assertThat(marketplace.unroutedBoard(100)).anyMatch(item -> item.incident().id().equals(incident.id()));
  }

  @Test
  void shouldShipNoDemoPartnersThroughMigrations() {
    var demo =
        db.sql("select count(*) from roadside_partner where id::text like 'a0000000-%' and online")
            .query(Integer.class)
            .single();

    assertThat(demo).isZero();
  }

  @Test
  void shouldReplaySameKeyAndRejectItWithADifferentRequest() {
    partner("Replay Tow", 0.02);
    var first = marketplace.createGeo(request(false), "geo-replay");

    var replay = marketplace.createGeo(request(false), "geo-replay");

    assertThat(replay.id()).isEqualTo(first.id());
    assertThatThrownBy(() -> marketplace.createGeo(request(true), "geo-replay"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("different request");
  }

  @Test
  void shouldRecordAcceptedQuoteAsDueAndNeverAsPaid() {
    var towing = partner("Test Tow", 0.01);
    var incident = marketplace.createGeo(request(false), "geo-due");
    var quote =
        incident.quotes().stream().filter(q -> q.partnerId().equals(towing)).findFirst().orElseThrow();

    marketplace.acceptQuote(customer, incident.id(), quote.id());
    marketplace.acceptQuote(customer, incident.id(), quote.id());

    var payment =
        db.sql("select status, amount_minor from roadside_payment where incident_id=:id")
            .param("id", incident.id())
            .query((rs, n) -> java.util.Map.entry(rs.getString(1), rs.getLong(2)))
            .single();
    assertThat(payment).isEqualTo(java.util.Map.entry("DUE", quote.totalAmountMinor()));
  }
}
