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

  @Test
  void shouldShipNoDemoPartnersThroughMigrations() {
    var demo =
        db.sql("select count(*) from roadside_partner where id::text like 'a0000000-%' and online")
            .query(Integer.class)
            .single();

    assertThat(demo).isZero();
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
