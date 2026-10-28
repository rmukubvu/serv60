package com.serv60.workshop.internal.application;

import static org.assertj.core.api.Assertions.*;

import com.serv60.errors.Conflict;
import com.serv60.errors.NotFound;
import com.serv60.support.FixedClock;
import com.serv60.support.PlatformIntegrationTest;
import com.serv60.workshop.api.VisitStage;
import com.serv60.workshop.api.WorkshopContracts.*;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

@PlatformIntegrationTest
@Transactional
class WorkshopServiceIntegrationTest {
  @Autowired WorkshopService workshop;
  @Autowired JdbcClient db;
  final UUID customer = UUID.randomUUID();
  /** The fixed clock reads 08:00 at the hub on 2035-01-01. */
  final OffsetDateTime now = OffsetDateTime.ofInstant(FixedClock.NOW, java.time.ZoneOffset.ofHours(2));
  UUID hub, bay, template;

  @BeforeEach
  void provision() {
    hub = provisionHub("Workshop test hub");
  }

  UUID provisionHub(String name) {
    var id = UUID.randomUUID();
    db.sql("insert into hub(id,name,timezone,latitude,longitude,address,published) values"
            + " (:hub,:name,'Africa/Johannesburg',-33.925,18.424,'Test location',true)")
        .param("hub", id).param("name", name).update();
    var resource = UUID.randomUUID();
    db.sql("insert into resource(id,hub_id,code,kind) values (:id,:hub,'EXPRESS-01','EXPRESS_BAY')")
        .param("hub", id).param("id", resource).update();
    var service = UUID.randomUUID();
    db.sql("insert into service_template(id,hub_id,code,name,duration_minutes) values (:id,:hub,'EXPRESS_OIL','Express',60)")
        .param("hub", id).param("id", service).update();
    if (id.equals(hub) || hub == null) {
      bay = resource;
      template = service;
    }
    return id;
  }

  UUID visit(OffsetDateTime startsAt, String status, UUID owner, UUID atHub) {
    var id = UUID.randomUUID();
    var serviceTemplate = db.sql("select id from service_template where hub_id=:hub").param("hub", atHub).query(UUID.class).single();
    db.sql("insert into visit_hold(id,hub_id,customer_id,service_template_id,starts_at,ends_at,status,policy,"
            + "expires_at,idempotency_key,appointment_contact,complimentary_service_code) values"
            + " (:id,:hub,:customer,:template,:starts,:ends,:status,'READY_STOCK',:ends,:key,cast(:contact as jsonb),'BARBER')")
        .param("id", id).param("hub", atHub).param("customer", owner).param("template", serviceTemplate)
        .param("starts", startsAt).param("ends", startsAt.plusHours(1)).param("status", status)
        .param("key", "workshop-" + id)
        .param("contact", "{\"customerName\":\"Alex Demo\",\"phoneNumber\":\"+27000000000\","
            + "\"vehicleMake\":\"Toyota\",\"vehicleModel\":\"Corolla\",\"registration\":\"DEMO 060\"}")
        .update();
    return id;
  }

  UUID confirmed(OffsetDateTime startsAt) {
    var id = visit(startsAt, "CONFIRMED", customer, hub);
    db.sql("insert into resource_allocation(id,hold_id,resource_id,starts_at,ends_at,status) values"
            + " (:id,:hold,:bay,:starts,:ends,'CONFIRMED')")
        .param("id", UUID.randomUUID()).param("hold", id).param("bay", bay)
        .param("starts", startsAt).param("ends", startsAt.plusHours(1)).update();
    return id;
  }

  /** Records every stage up to {@code target}, continuing from wherever the visit already is. */
  FloorVisit walkTo(UUID id, VisitStage target) {
    var recorded = db.sql("select stage from visit_stage_event where hold_id=:id").param("id", id)
        .query(String.class).list().stream().map(VisitStage::valueOf).max(Comparator.naturalOrder());
    FloorVisit row = null;
    var stage = recorded.orElse(null);
    if (stage == null) {
      row = workshop.checkIn(hub, id, null, "tester");
      stage = VisitStage.CHECKED_IN;
    }
    while (stage != target) {
      stage = stage.next().orElseThrow();
      row = workshop.recordStage(hub, id, stage, null, "tester");
    }
    return row;
  }

  long events(String type, UUID aggregate) {
    return db.sql("select count(*) from outbox_event where event_type=:type and aggregate_id=:id")
        .param("type", type).param("id", aggregate).query(Long.class).single();
  }

  @Test
  void shouldShowNoProgressBeforeCheckIn() {
    var id = confirmed(now);

    var progress = workshop.progress(customer, id);

    assertThat(progress.stage()).isNull();
    assertThat(progress.elapsedMinutes()).isNull();
    assertThat(progress.voucherCode()).isNull();
    assertThat(progress.events()).isEmpty();
    assertThat(progress.targetMinutes()).isEqualTo(60);
    assertThat(progress.hubName()).isEqualTo("Workshop test hub");
    assertThat(progress.complimentaryServiceCode()).isEqualTo("BARBER");
  }

  @Test
  void shouldCheckInAndIssueACoffeeVoucher() {
    var id = confirmed(now);

    var row = workshop.checkIn(hub, id, " Keys in box 4 ", "tester");

    assertThat(row.stage()).isEqualTo("CHECKED_IN");
    assertThat(row.elapsedMinutes()).isZero();
    assertThat(row.nextStages()).containsExactly("INSPECTING");
    assertThat(row.bayCode()).isEqualTo("EXPRESS-01");
    assertThat(row.customerName()).isEqualTo("Alex Demo");
    assertThat(row.vehicle()).isEqualTo("Toyota Corolla");
    assertThat(row.registration()).isEqualTo("DEMO 060");
    var progress = workshop.progress(customer, id);
    assertThat(progress.voucherCode()).isEqualTo(WorkshopService.voucherCode(id)).startsWith("COFFEE-");
    assertThat(progress.events()).singleElement().satisfies(event -> {
      assertThat(event.stage()).isEqualTo("CHECKED_IN");
      assertThat(event.note()).isEqualTo("Keys in box 4");
    });
    assertThat(events("VisitStageRecorded", id)).isEqualTo(1);
  }

  @Test
  void shouldRecordStagesOnlyInOrderAndOnce() {
    var id = confirmed(now);
    workshop.checkIn(hub, id, null, "tester");

    assertThatThrownBy(() -> workshop.recordStage(hub, id, VisitStage.SERVICING, null, "tester"))
        .isInstanceOf(Conflict.class).hasMessage("Record INSPECTING before SERVICING");
    assertThatThrownBy(() -> workshop.checkIn(hub, id, null, "tester"))
        .isInstanceOf(Conflict.class).hasMessage("Record INSPECTING before CHECKED_IN");
    assertThat(workshop.recordStage(hub, id, VisitStage.INSPECTING, null, "tester").stage()).isEqualTo("INSPECTING");
  }

  @Test
  void shouldWalkAVisitToHandoverAndThenStop() {
    var id = confirmed(now);

    var row = walkTo(id, VisitStage.HANDED_OVER);

    assertThat(row.stage()).isEqualTo("HANDED_OVER");
    assertThat(row.handedOverAt()).isNotNull();
    assertThat(row.readyAt()).isNotNull();
    assertThat(row.nextStages()).isEmpty();
    assertThatThrownBy(() -> workshop.recordStage(hub, id, VisitStage.READY, null, "tester"))
        .isInstanceOf(Conflict.class).hasMessage("This visit has been handed over");
    assertThat(workshop.progress(customer, id).events()).hasSize(VisitStage.values().length);
  }

  @Test
  void shouldCountElapsedTimeFromCheckInAndFreezeItOnceReady() {
    var id = confirmed(now);
    workshop.checkIn(hub, id, null, "tester");
    db.sql("update visit_stage_event set recorded_at=recorded_at - interval '35 minutes' where hold_id=:id")
        .param("id", id).update();

    assertThat(workshop.progress(customer, id).elapsedMinutes()).isEqualTo(35);
    var ready = walkTo(id, VisitStage.READY);

    assertThat(ready.elapsedMinutes()).isEqualTo(35);
    assertThat(workshop.progress(customer, id).readyAt()).isEqualTo(now);
  }

  @Test
  void shouldProposeExtraWorkAndLetTheCustomerApproveIt() {
    var id = confirmed(now);
    workshop.checkIn(hub, id, null, "tester");
    var request = new ProposeScopeChange(
        List.of(new ScopeItem("Front brake pads", 145000), new ScopeItem("Wiper blades", 32000)), " Pads at 2 mm ");

    var proposal = workshop.propose(hub, id, request, "tester");

    assertThat(proposal.status()).isEqualTo("PROPOSED");
    assertThat(proposal.totalMinor()).isEqualTo(177000);
    assertThat(proposal.note()).isEqualTo("Pads at 2 mm");
    assertThat(proposal.items()).hasSize(2);
    assertThat(workshop.floor(hub)).singleElement().satisfies(row -> {
      assertThat(row.pendingScopeChanges()).isEqualTo(1);
      assertThat(row.approvedExtrasMinor()).isZero();
    });
    assertThat(workshop.progress(customer, id).scopeChanges()).singleElement()
        .satisfies(change -> assertThat(change.items().getFirst().description()).isEqualTo("Front brake pads"));

    var decided = workshop.decide(customer, id, proposal.id(), "APPROVE");

    assertThat(decided.scopeChanges()).singleElement().satisfies(change -> {
      assertThat(change.status()).isEqualTo("APPROVED");
      assertThat(change.decidedAt()).isEqualTo(now);
    });
    assertThat(workshop.floor(hub).getFirst().approvedExtrasMinor()).isEqualTo(177000);
    assertThat(workshop.floor(hub).getFirst().pendingScopeChanges()).isZero();
    assertThatThrownBy(() -> workshop.decide(customer, id, proposal.id(), "DECLINE"))
        .isInstanceOf(Conflict.class).hasMessage("This proposal was already approved");
    assertThat(events("ScopeChangeProposed", id)).isEqualTo(1);
    assertThat(events("ScopeChangeDecided", id)).isEqualTo(1);
  }

  @Test
  void shouldLetTheCustomerDeclineExtraWork() {
    var id = confirmed(now);
    var proposal = workshop.propose(hub, id, new ProposeScopeChange(List.of(new ScopeItem("Cabin filter", 45000)), null), "tester");

    var decided = workshop.decide(customer, id, proposal.id(), "DECLINE");

    assertThat(decided.scopeChanges().getFirst().status()).isEqualTo("DECLINED");
    assertThat(workshop.floor(hub).getFirst().approvedExtrasMinor()).isZero();
  }

  @Test
  void shouldRefuseExtraWorkOnceReadyOrBeforeConfirmation() {
    var ready = confirmed(now);
    walkTo(ready, VisitStage.READY);
    var held = visit(now.plusHours(2), "HELD", customer, hub);
    var request = new ProposeScopeChange(List.of(new ScopeItem("Cabin filter", 45000)), null);

    assertThatThrownBy(() -> workshop.propose(hub, ready, request, "tester"))
        .isInstanceOf(Conflict.class).hasMessage("Extra work cannot be proposed once the vehicle is ready");
    assertThatThrownBy(() -> workshop.propose(hub, held, request, "tester"))
        .isInstanceOf(Conflict.class).hasMessage("Extra work can only be proposed on a confirmed visit");
    assertThatThrownBy(() -> workshop.checkIn(hub, held, null, "tester"))
        .isInstanceOf(Conflict.class).hasMessage("Only a confirmed visit can be on the floor");
  }

  @Test
  void shouldHideOtherCustomersVisitsAndOtherHubsFloors() {
    var id = confirmed(now);
    var otherHub = provisionHub("Other hub");

    assertThatThrownBy(() -> workshop.progress(UUID.randomUUID(), id)).isInstanceOf(NotFound.class);
    assertThatThrownBy(() -> workshop.checkIn(otherHub, id, null, "tester")).isInstanceOf(NotFound.class);
    assertThatThrownBy(() -> workshop.decide(customer, id, UUID.randomUUID(), "APPROVE"))
        .isInstanceOf(NotFound.class).hasMessage("Extra work proposal not found");
    assertThatThrownBy(() -> workshop.floor(UUID.randomUUID())).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void shouldIssueAnInvoiceAtHandoverWithServiceOptionAndApprovedExtras() {
    var id = confirmed(now);
    walkTo(id, VisitStage.INSPECTING);
    var approved = workshop.propose(hub, id, new ProposeScopeChange(
        List.of(new ScopeItem("Front brake pads", 145000), new ScopeItem("Wiper blades", 32000)), null), "tester");
    workshop.decide(customer, id, approved.id(), "APPROVE");
    var declined = workshop.propose(hub, id, new ProposeScopeChange(List.of(new ScopeItem("Cabin filter", 45000)), null), "tester");
    workshop.decide(customer, id, declined.id(), "DECLINE");
    assertThatThrownBy(() -> workshop.invoice(customer, id)).isInstanceOf(NotFound.class);

    var row = walkTo(id, VisitStage.HANDED_OVER);

    var invoice = workshop.invoice(customer, id);
    assertThat(invoice.number()).isEqualTo("S60-20350101-" + id.toString().substring(0, 6).toUpperCase());
    assertThat(invoice.lines()).extracting(InvoiceLine::description, InvoiceLine::amountMinor, InvoiceLine::status)
        .containsExactly(
            tuple("Express vehicle service", 24900L, "DUE"),
            tuple("Barber · complimentary", 0L, "INCLUDED"),
            tuple("Front brake pads", 145000L, "DUE"),
            tuple("Wiper blades", 32000L, "DUE"));
    assertThat(invoice.totalMinor()).isEqualTo(201900);
    assertThat(invoice.paidMinor()).isZero();
    assertThat(invoice.dueMinor()).isEqualTo(201900);
    assertThat(invoice.customerName()).isEqualTo("Alex Demo");
    assertThat(invoice.vehicle()).isEqualTo("Toyota Corolla");
    assertThat(invoice.issuedAt()).isEqualTo(now);
    assertThat(row.invoiceNumber()).isEqualTo(invoice.number());
    assertThat(row.invoiceTotalMinor()).isEqualTo(201900);
    assertThat(events("VisitInvoiced", id)).isEqualTo(1);
  }

  @Test
  void shouldMarkTheServicePaidWhenItWasPaidInTheApp() {
    var id = confirmed(now);
    db.sql("insert into payment_order(id,customer_id,hold_id,amount_minor,currency,provider,status,idempotency_key,"
            + "request_hash,created_at,updated_at) values (:id,:customer,:hold,26000,'ZAR','YOCO','PAID',:key,'h',now(),now())")
        .param("id", UUID.randomUUID()).param("customer", customer).param("hold", id).param("key", "pay-" + id).update();

    walkTo(id, VisitStage.HANDED_OVER);

    var invoice = workshop.invoice(customer, id);
    assertThat(invoice.lines().getFirst()).isEqualTo(new InvoiceLine("Express vehicle service", 26000, "PAID"));
    assertThat(invoice.paidMinor()).isEqualTo(26000);
    assertThat(invoice.dueMinor()).isZero();
  }

  @Test
  void shouldListTheCustomersServiceHistoryNewestFirst() {
    var older = confirmed(now.minusDays(2));
    walkTo(older, VisitStage.HANDED_OVER);
    db.sql("update visit_invoice set issued_at=issued_at - interval '2 days' where hold_id=:id").param("id", older).update();
    var newer = confirmed(now);
    walkTo(newer, VisitStage.HANDED_OVER);
    var otherCustomers = visit(now, "CONFIRMED", UUID.randomUUID(), hub);
    walkTo(otherCustomers, VisitStage.HANDED_OVER);
    confirmed(now.plusHours(2));

    var history = workshop.serviceHistory(customer, 20);

    assertThat(history).extracting(ServiceRecord::visitId).containsExactly(newer, older);
    assertThat(history.getFirst().registration()).isEqualTo("DEMO 060");
    assertThat(history.getFirst().hubName()).isEqualTo("Workshop test hub");
    assertThat(workshop.serviceHistory(customer, 1)).hasSize(1);
    assertThatThrownBy(() -> workshop.serviceHistory(customer, 0)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> workshop.invoice(UUID.randomUUID(), newer)).isInstanceOf(NotFound.class);
  }

  @Test
  void shouldNameEachComplimentaryOption() {
    assertThat(WorkshopService.optionName("MASSAGE")).isEqualTo("Massage");
    assertThat(WorkshopService.optionName("PRIVATE_WORK")).isEqualTo("Private workspace");
    assertThat(WorkshopService.optionName("OTHER")).isEqualTo("OTHER");
  }

  @Test
  void shouldListTodaysConfirmedVisitsAndCarsStillOnTheFloor() {
    var today = confirmed(now);
    var laterToday = confirmed(now.plusHours(3));
    visit(now.plusHours(5), "HELD", customer, hub);
    confirmed(now.plusDays(1));
    var otherHub = provisionHub("Other hub");
    visit(now, "CONFIRMED", customer, otherHub);
    var yesterdayOnFloor = confirmed(now.minusDays(1));
    walkTo(yesterdayOnFloor, VisitStage.WASHING);
    var yesterdayDone = confirmed(now.minusDays(1).plusHours(2));
    walkTo(yesterdayDone, VisitStage.HANDED_OVER);

    var floor = workshop.floor(hub);

    assertThat(floor).extracting(FloorVisit::visitId).containsExactly(yesterdayOnFloor, today, laterToday);
    assertThat(floor.getFirst().stage()).isEqualTo("WASHING");
    assertThat(floor.get(1).stage()).isNull();
    assertThat(floor.get(1).nextStages()).containsExactly("CHECKED_IN");
  }

  @Test
  void shouldFindTheVisitsTheSimulationMayMove() {
    var arrived = confirmed(now.minusMinutes(30));
    confirmed(now.minusHours(4));
    confirmed(now.plusHours(1));
    var checkedIn = confirmed(now.minusMinutes(10));
    workshop.checkIn(hub, checkedIn, null, "tester");
    var waitingOnCustomer = confirmed(now.minusMinutes(20));
    workshop.checkIn(hub, waitingOnCustomer, null, "tester");
    workshop.propose(hub, waitingOnCustomer, new ProposeScopeChange(List.of(new ScopeItem("Pads", 1000)), null), "tester");
    var ready = confirmed(now.minusMinutes(50));
    walkTo(ready, VisitStage.READY);

    assertThat(workshop.awaitingCheckIn(FixedClock.NOW))
        .extracting(WorkshopService.SimulationCandidate::id).containsExactly(arrived);
    assertThat(workshop.onTheFloor()).filteredOn(candidate -> candidate.hub().equals(hub))
        .singleElement().satisfies(candidate -> {
          assertThat(candidate.id()).isEqualTo(checkedIn);
          assertThat(candidate.stage()).isEqualTo(VisitStage.CHECKED_IN);
          assertThat(candidate.since()).isEqualTo(FixedClock.NOW);
        });
  }
}
