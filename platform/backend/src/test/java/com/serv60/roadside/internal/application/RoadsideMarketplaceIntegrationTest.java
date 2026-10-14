package com.serv60.roadside.internal.application;

import static org.assertj.core.api.Assertions.*;

import com.serv60.roadside.api.RoadsideContracts.*;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.*;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;

@SpringBootTest(
    properties = {"serv60.booking.expiry-sweep-enabled=false", "serv60.security.enabled=false"})
@Transactional
class RoadsideMarketplaceIntegrationTest {
  static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:18-alpine");

  static {
    PG.start();
  }

  @AfterAll
  static void stop() {
    PG.stop();
  }

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", PG::getJdbcUrl);
    r.add("spring.datasource.username", PG::getUsername);
    r.add("spring.datasource.password", PG::getPassword);
  }

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
    return new CreateGeoIncident(
        customer, null, LAT, LON, "M1 north", "Flat tyre", "Workshop", LAT + 0.05, LON,
        rideHome, rideHome ? "Home" : null, null, null);
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

    var dispatched = marketplace.acceptQuote(customer, incident.id(), quoteFrom(incident, quoted).id());

    assertThat(dispatched.status()).isEqualTo("EN_ROUTE");
    assertThat(dispatched.truckLabel()).isEqualTo("Quoted Tow");
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
    var incident = marketplace.createGeo(request(true), "geo-ride");
    assertThat(incident.ride().status()).isEqualTo("QUOTED");

    var accepted = marketplace.acceptQuote(customer, incident.id(), quoteFrom(incident, tow).id());
    var moved =
        marketplace.startRide(customer, incident.id(), new StartRide("Office", LAT + 0.1, LON));

    assertThat(accepted.ride())
        .extracting(RideLeg::status, RideLeg::trackingUrl, RideLeg::pinCode)
        .containsExactly("REQUESTED", null, null);
    assertThat(moved.ride().dropoffLabel()).isEqualTo("Office");
    assertThat(marketplace.dispatchBoard(100))
        .anySatisfy(item -> assertThat(item.incident().id()).isEqualTo(incident.id()));
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
