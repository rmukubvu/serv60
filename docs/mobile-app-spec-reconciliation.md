# Serv60 mobile application — reconciled specification

**Status:** proposed reconciliation for review; it records what exists, what the draft specification adds, and which decisions are still open. It authorises no build work on its own.

**Date:** 23 October 2026, checked against `main` at commit `0c772cc`.

**Source:** an externally drafted document titled "Serv60 Mobile Application: Technical Specification & Architecture Document" (undated), called **the draft** below. The draft describes native iOS and Android apps on a Node.js or Go microservice backend. Serv60 already has a Flutter customer app, a Java modular monolith and a Next.js operations console, designed in the [platform HLD](HLD-serv60-platform.md) and running from [`platform/`](../platform/README.md). This document keeps the draft's product intent and maps it onto that platform.

**Audience:** founders, product, hub operations and engineering.

## 1. Summary

- Section 2 replaces the draft's technology section with the stack that is built. No native rewrite, second backend language, Redis, message broker or WebSocket layer is proposed.
- Of the draft's six modules, parts of **A (identity and garage)** and **F (roadside)** exist today. **B (warranty pre-authorisation)**, the stage tracker and countdown in **C**, **D (shortfall approval)** and **E (engagement)** exist neither in the HLD nor in code. Live video streaming is recommended out of scope.
- Four shared capabilities gate most of the new modules: push notifications, a workshop-execution context that records check-in and stage events, evidence photos in private storage, and a decision on stored payment methods. Section 5 lists them.
- Sixteen product decisions are open (section 6). Several change the business model, not just the app: whether Serv60 does warranty work, whether roadside is an approved-partner service or an open marketplace, and whether the platform bills partners.
- Section 7 proposes a sequence aligned with the HLD's delivery phases and names the architecture decision records to write before code.

## 2. The platform as built (replaces draft section 1)

| Area | Draft | Serv60 platform | Where |
|---|---|---|---|
| Customer app | Native Swift/SwiftUI and Kotlin/Compose, or Flutter/React Native | **Flutter** with Material; four tabs: Explore, Bookings, Roadside, Account | [`platform/mobile`](../platform/mobile/README.md) |
| App state | Redux / BLoC / Combine | Plain widgets with repository classes per feature; no state-management library | `platform/mobile/lib/src` |
| Backend | Node.js or Go microservices on AWS or Azure | **Java 25, Spring Boot 4.1.1, Spring Modulith 2.1.1** modular monolith. Modules: `booking`, `hubs`, `identity`, `payment`, `roadside`, `concierge`, `ops`; boundaries verified in CI | [`platform/backend`](../platform/backend) |
| Database | PostgreSQL and Redis | **PostgreSQL 18**, Flyway migrations V1–V27, `JdbcClient`. No Redis. Distance is a haversine expression in SQL; PostGIS is not installed | `platform/backend/src/main/resources/db/migration` |
| Background work | Celery / BullMQ | Spring scheduled sweeps (hold expiry, roadside quote expiry, outbox retention) and a **transactional outbox leased to SQS** with backoff and dead-lettering | `booking/internal/application` |
| Real-time updates | WebSockets, sub-second | **Polling**: the roadside screen refreshes every 8 seconds while visible; the HLD sizes operations dashboards at a 30-second refresh and defers WebSockets until concurrency warrants them | [`roadside_page.dart`](../platform/mobile/lib/src/roadside/roadside_page.dart) |
| Push notifications | FCM / APNs high-priority channels | **Not integrated.** No Firebase or APNs dependency in the app; no device-token registry in the API | — |
| Payments | Paystack or Yoco, tokenised cards, 3-D Secure 2.0 | **Yoco hosted checkout** per payment order; the signed webhook is the only proof of payment; the hold is extended once so 3-D Secure cannot lose the slot. Provider identifiers `YOCO`, `PEACH`, `PAYFAST` are reserved. **No stored card, no merchant-initiated charge** | [`PAYMENTS.md`](../platform/PAYMENTS.md), `payment/` |
| Identity | OTP by SMS or email; Face ID / Touch ID | Customers: **Clerk** (Google) through OIDC authorization code with PKCE; name and email read-only from Clerk. Staff: demo email session locally, Auth0 planned. API: JWT resource server with audience validation | [`CLERK_SETUP.md`](../platform/CLERK_SETUP.md), `security/` |
| Operations console | Not mentioned ("WMS floor tablets") | **Next.js 16.4, React 19, TanStack Query**: today-at-the-hub, operator insights, service-floor calendar, roadside dispatch board, parts readiness, hub settings | [`platform/operations-web`](../platform/operations-web) |
| Hosting | AWS or Azure | **AWS af-south-1** target: ECS Fargate, RDS PostgreSQL Multi-AZ, SQS, CloudFront and WAF, private S3 for evidence. Local: Docker Compose with PostgreSQL and LocalStack | HLD §9, [`platform/infra`](../platform/infra) |
| Secrets | AWS Secrets Manager | Same target; environment variables locally, never in client builds | [runbook](customer-journey-runbook.md) |
| Video streaming | WebRTC / HLS from facility CCTV | None | — |
| API contract | Not mentioned | OpenAPI for the customer, hub and dispatch surfaces | [`contracts/openapi.yaml`](../platform/contracts/openapi.yaml) |

The draft also omits the parts of the platform that carry the business risk: atomic visit holds with idempotency keys, parts readiness against usable stock, the hold-to-payment-to-confirmation flow, hub capacity and trading hours, and the dispatcher-confirmed roadside lifecycle. Every module below has to fit those rules rather than sit beside them. The [ubiquitous language](../UBIQUITOUS_LANGUAGE.md) remains the vocabulary.

## 3. Vocabulary

The draft introduces terms that collide with words the platform already uses. Adopt the right-hand column.

| Draft term | Collision or gap | Use instead |
|---|---|---|
| "T-48 pre-authorisation gate" | The platform's 48 hours is the inclusive **change cutoff** for rescheduling ([ADR 0014](adr/0014-customer-concierge.md)) | **Cover Check** with its own configurable lead time; never "48 hours" unqualified |
| "60-Minute Service Countdown" | The **Express Visit Target** covers check-in to handover; the glossary lists "guaranteed repair time" as wording to avoid. The draft's timer starts at bay entry and measures something else | **Visit Progress** against the target; see decision 4 |
| "Shortfall" | No platform term; the glossary has **Scope Change** and **Accepted Quote** | **Scope Change**; the **shortfall** is the customer-payable part of a scope change after any cover |
| "Hard-Block" / `is_shortfall_cleared` | The platform separates lifecycle from readiness (**Ready for Express**, **Conditional Appointment**, **Parts-Readiness Deadline**) | An unapproved scope change past its **Approval Deadline** means the appointment is not Ready for Express |
| "Lead", "first right of refusal" | The glossary defines **Dispatch Offer**: an expiring request for an eligible partner to accept a tow job | **Dispatch Offer**; the charge is a **Dispatch Fee** |
| "Tow operator", "general driver pool" | The platform has **Approved Tow Partners** only | **Approved Tow Partner**; an open pool is decision 9 |
| "Tokenised wallet" | No platform concept | **Stored Payment Method** (customer) and **Partner Account** (billing) |
| "Booking engine", "WMS" | — | Booking module; operations console |
| "User" | Glossary: **Customer** or **User Identity** | As the glossary |

## 4. Module reconciliation

Each module lists what the draft asks for, what exists, the gap, where it fits and the decisions it waits on.

### A. Identity and vehicle garage

**Draft:** OTP sign-in by SMS or email, biometric unlock, a garage with VIN, make, model, plate, mileage and warranty or service-plan provider, VIN decoding to auto-fill specifications.

**Exists:** Clerk sign-in; profile with name and phone; up to five vehicles per customer with make, model, registration and a default flag (migration V17). A roadside request requires an owned vehicle; a booking may reference one, and only one the customer owns.

**Gap:** VIN, mileage, year or variant, warranty or service-plan provider and policy reference; VIN decoding; phone OTP; biometric re-authentication.

**Fit and notes:**

- Biometric unlock is a device-local re-authentication in front of the stored Clerk session. Small, self-contained, no API change.
- Phone OTP is an identity-provider configuration, not a second sign-up flow. SMS cost sits outside the cloud budget in HLD §9.
- VIN decoding needs a commercially licensed data source. The HLD already lists a "licensed fitment source" as an open decision for parts planning; one licence should serve both.
- Warranty fields are only worth adding with module B. Mileage is useful on its own for service eligibility.

**Decisions:** 14, 15.

### B. Warranty pre-authorisation (Cover Check)

**Draft:** a background job 48 hours before the slot checks the customer's policy with warranty administrators by API or EDI, verifies cover, caps and excess, and routes failures to an in-house claims specialist.

**Exists:** nothing. The HLD scopes booking as customer-paid express servicing and roadside as direct-pay "without submitting an insurance claim". No policy record, insurer connector, claims role or pre-authorisation state exists.

**Gap:** all of it: policy records on the vehicle, a cover-check state on the appointment, administrator connectors, a specialist worklist in the operations console, consent for sharing data with insurers, and reporting.

**Reality check:** the draft assumes API or EDI access to named administrators. None of that has been verified. Treat portal or email submission by a person as the baseline until an administrator confirms an integration channel and a contract. The draft's "fallback" is the launch design.

**Fit if adopted:**

- A new bounded context (`cover`) owning policy and cover-check records; the booking module exposes an appointment's cover state the way it exposes payment state.
- Cover state per appointment: `NOT_APPLICABLE`, `PENDING`, `AUTHORISED`, `DECLINED`, `NEEDS_SPECIALIST`, with authorised amount, excess and reference.
- The check is a **persisted due-time job**, claimed by any instance, as HLD §8 requires for reminders and expiry. Not a process-local timer.
- Outbox events (`CoverCheckDue`, `CoverDecided`) feed notifications and the specialist worklist.
- Administrator credentials in Secrets Manager; no administrator call inside a database transaction.
- A declined or partial cover produces a scope change (module D) before the visit, which is where the draft's "T-24 hard block" belongs.

**Decisions:** 1, 2, 3, 16.

### C. Visit tracking, the 60 minutes and "see my car"

**Draft:** a milestone tracker (Check-In → Inspection Bay → Express Bay → Quality Control → Wash/Detail → Ready for Collection), a prominent countdown timer from bay entry, live 4K CCTV streaming free on site and R45 per day off site, all over sub-second WebSockets.

**Exists:** the visit lifecycle is `HELD`, `CONFIRMED`, `CANCELLED`, `EXPIRED`. The operations calendar shows held and confirmed visits. HLD §5 defines the stage timeline (check-in and inspection 00–05, maintenance 05–40, quality check 40–45, wash 45–55, handover 55–60) and HLD §8 names the records for it (`work_order`, `stage_event`, `actual_occupancy`, `quality_check`, `handover`). None is implemented. Roadside tracking polls every 8 seconds.

**Gap:** a **Workshop Execution** context with check-in, stage events and handover; a tablet-friendly way for staff to record them; a visit-status screen in the app; push notifications for milestones.

**Fit and notes:**

- Use the HLD's stage names. "Inspection Bay" and "Express Bay" as separate physical stops are not the resource model: inspection happens in the service bay during the first five minutes.
- The 60 minutes is a target for the whole eligible visit, not a bay timer. Show **elapsed time since check-in against the target with the stages reached**, in the wording the README uses. A countdown that hits zero reads as a broken promise and the glossary forbids "guaranteed" language. Decision 4 settles this.
- Real-time: push for each stage event plus a 10–15 second poll while the status screen is visible, with change tokens so unchanged polls are cheap. At the HLD's sizing (20–28 express visits per hub per day) this is well inside budget; WebSockets stay deferred as HLD §7 says.
- **Live streaming is recommended out of scope.** Staff and other customers' vehicles are in frame, which creates a new category of personal information with its own POPIA assessment. HLS adds 6–30 seconds of delay, so it is not "live". 4K adds nothing on a phone and the bandwidth is not in the HLD budget. The R45 per day fee needs a stored payment method that does not exist (decision 8).
- The need behind streaming is reassurance. Serve it with **stage photos**: the technician attaches one or two photos when recording a stage event, stored in the private S3 evidence bucket with short-lived signed URLs that HLD §9 already plans. The same mechanism carries module D's photographic proof and roadside custody photos.

**Decisions:** 4, 5, 6.

### D. Scope change and shortfall approval

**Draft:** when cover caps or non-covered items leave a shortfall, send a high-priority push with photos, technician notes and the amount; one-tap approve or pay; if unresolved past T-24, block the vehicle from the express queue.

**Exists:** the glossary defines Scope Change ("proposed additional or altered work requiring customer approval of timing and charges") and Accepted Quote. The payment module creates server-priced, idempotent payment orders; refunds and reconciliation are handled; the operations console shows critical payment cases per hub. No scope-change record, endpoint or screen exists.

**Gap:** a `scope_change` record with versioned line items, photos, notes, cover-covered and customer-payable amounts and an approval deadline; customer approve or decline; a payment order for the shortfall using the existing pattern; an operations screen to raise one; push.

**Fit and notes:**

- Two moments produce scope changes: **pre-visit** from a cover decision (module B) and **in-visit** from inspection in the first minutes of the hour. Both share the record; only the deadline differs.
- The "hard block" is not a boolean on the appointment. The platform already treats confirmed and ready as distinct states ("an appointment may be confirmed but parts-pending"). An unapproved scope change past its deadline makes the appointment **not Ready for Express**, shown on the calendar like a parts-pending visit. A single `is_shortfall_cleared` flag would hide why.
- Pay-now works today through hosted checkout, which is a redirect, not one tap. One tap needs a stored payment method (decision 8). Approval without immediate payment, settled at handover, is the alternative.
- Declining is a real branch: proceed without the item, reschedule, or cancel. The hub needs a rule (decision 7).

**Decisions:** 7, 8.

### E. Engagement hub

**Draft:** a "Play" button with rewards, achievements, early-arrival vouchers (15% parts discount or a coffee), referral bonuses, and remote configuration so campaigns ship without app releases.

**Exists:** nothing. The HLD places loyalty after the core launch.

**Fit and notes:**

- The only item with an engineering dependency is the early-arrival voucher, which needs a check-in time against the appointment start. That comes free with the Workshop Execution context.
- Remote configuration does not need a new service. A versioned, hub-scoped configuration served by the API, in the style of hub settings ([ADR 0015](adr/0015-hub-operations-settings.md)), covers campaigns.
- Everything else is commercial design (discount levels, partner for coffee, referral attribution and fraud rules) and should wait for data from real visits.

**Decisions:** 13.

### F. Roadside

**Draft:** emergency tow request with session-bound location, destination choice (home, Serv60, independent dealer, panel beater), a cascade to three priority-subscribed operators in the zone before the general pool, the operator bills the motorist and pays a R250 lead fee from a wallet, masked calling.

**Exists:** the most complete module. Nearby trucks within 25 km with distance and ETA; quotes from callout plus per-kilometre rates with a ten-minute expiry; the customer accepts one; the partner's capacity is reserved; a dispatcher phones the partner and confirms, which moves the incident to `EN_ROUTE`, then `ON_SCENE`, `TOWING`, `COMPLETED`; cancellation allowed by the customer until confirmation and by the hub after; an optional ride-home leg (no ride provider integrated); amounts recorded as `DUE` and voided on cancellation; at most three open requests per customer and 150 km to a customer destination; incidents routed to the nearest published hub with an admin queue for unrouted ones; a dispatch board ranked by urgency; foreground location only on a deliberate button press. The tow destination defaults to the nearest hub or can be customer coordinates.

**Gap against the draft:**

1. **Destination types.** Coordinates are supported; there is no labelled destination (home, dealer, panel beater) and no record of who receives the vehicle.
2. **Partner offers and cascade.** Partners have no tier, zone or subscription; there is no partner app or web page; acceptance is by phone through the dispatcher. HLD §7 already specifies the target: expiring `dispatch_offer` records with a dispatch generation, one active assignment per tow job, accepted in a transaction that checks expiry against database time.
3. **Dispatch fee.** No partner account, ledger or invoicing. Charging the partner makes the partner a billed customer of the platform, with VAT invoices and collection, which is a different capability from customer card payments.
4. **Masked calling.** None. The customer's callback number is on the profile; the dispatcher relays today.
5. **Location retention.** Pickup, destination and confirmed-truck coordinates are stored on the incident and never purged. The draft's "purge immediately on completion" conflicts with disputes and partner billing; a retention window is needed instead (decision 11).

**Model conflicts to resolve, not paper over:**

- **Approved partners versus an open pool.** The HLD launches with contracted regional partners and says not to require every tow company to build an API. A general driver pool means onboarding, vetting and insurance checks the platform does not model.
- **Feeder versus neutral.** The platform routes an incident to the nearest hub and defaults the destination there, because a tow to Serv60 becomes a repair intake. A neutral marketplace with independent destinations is compatible, but the hub-routing rule and the dispatch board's priorities should say which destinations the hub cares about.
- **Who pays whom.** Today the quote is recorded and collected outside the platform, which matches "the operator bills the motorist directly". A dispatch fee is new revenue and needs decision 10 before it is designed.

**Fit if adopted:** partner tier and zone columns; `dispatch_offer` as HLD §7 specifies, with the cascade as a persisted due-time job that opens the next offers when the current ones expire; a partner mobile web page for accept and status (HLD phase 3) with push; the dispatcher stays as fallback and for phoned-in requests. The dispatch fee as a ledger entry on a partner account, collected by monthly invoice or debit order rather than a card token. A bounded job that nulls incident coordinates after the retention window.

**Decisions:** 9, 10, 11, 12.

### Compliance and privacy

**Draft:** ephemeral session-bound location, explicit consent gates for processing, card tokenisation and insurer sharing, AES-256 at rest and TLS 1.3 in transit.

**Exists:** foreground-only location on user action; profile and assistant responses sent with `no-store`; logs carry trace and request ids but no names, numbers or coordinates; secrets never reach client builds; webhook signatures and token audiences verified; HLD §11 covers encryption, Secrets Manager, row-level security and retention rules.

**Gap:** no **consent record** (purpose, version, granted and revoked times) for processing, notifications, marketing or insurer sharing; no location retention job; no notification preferences. Streaming would add bystander and staff footage, which is why module C recommends against it. HLD §11 already notes that hosting in South Africa does not by itself settle data residency: Clerk, Yoco, OpenRouter, push providers and any insurer are processors to list in a data-processing register.

**Fit:** a `customer_consent` table in the identity module, checked by the features that need it; a retention job in roadside; a processing register in `docs/` before launch.

**Decisions:** 11, 16.

## 5. Shared capabilities the modules depend on

In the order they unblock work.

1. **Push notifications** (B, C, D, F). FCM and APNs through the Flutter app; a device-token registry keyed by customer; an outbox consumer that turns events into notifications; a preferences record. Every "high-priority alert" in the draft is this.
2. **Workshop Execution context** (C, D, E). Check-in, stage events, actual occupancy, quality check and handover, recorded by staff on the operations console or a tablet view of it. HLD phase 2 already lists it.
3. **Evidence media** (C, D, F). Private S3 with direct authorised uploads, size and type bounds, scanning and short-lived signed reads, as HLD §9 specifies. Serves stage photos, scope-change proof and roadside custody photos.
4. **Stored payment method or merchant-initiated charges** (C streaming fee, D one-tap pay, F if the fee were card-based). Today there is none. Decision 8 decides whether to add one and with which provider.
5. **Partner accounts and billing** (F). A ledger, invoices and collection for money the platform is owed by partners. Separate from customer payments.
6. **Persisted due-time jobs** (B, D, F). The expiry sweeps already follow the pattern; generalise it to a claimed `due_work` table rather than adding one scheduler per feature.
7. **Consent and retention** (all). The consent record and bounded retention jobs above.

## 6. Open product decisions

Each blocks the modules in brackets. Owners are the business unless noted.

1. **Warranty positioning.** Does Serv60 perform warranty or service-plan work at launch, or stay customer-paid? [A fields, B, D pre-visit]
2. **Administrators and channels.** Which administrators, under what contract, and by which channel (API, portal, email) each accepts a pre-authorisation. Verify before design. [B]
3. **Claims specialist.** Who performs manual cover checks, during which hours, and what happens to a visit still `PENDING` at arrival. [B]
4. **What the 60 minutes means in the app.** Elapsed time against the target from check-in, or a countdown; when it starts; the wording when the target is exceeded. [C]
5. **Stage names and who records them.** HLD stages or the draft's; technician on a tablet or a service advisor at the console. [C]
6. **"See my car".** Stage photos, live video, or neither. [C]
7. **Scope-change rules.** Deadline for pre-visit shortfalls; expected response time for in-visit ones; what the hub does on decline or silence. [D]
8. **Stored payment methods.** Whether to offer one, which provider supports merchant-initiated charges under 3-D Secure, and the consent wording. [C, D, F]
9. **Roadside model.** Approved partners only, or an open pool; which destination types are offered; whether partner tiers exist and what priority costs a partner. [F]
10. **Dispatch fee.** Amount, when it is earned (acceptance, arrival or completion), what happens on cancellation, and invoicing with VAT. [F]
11. **Location retention.** How long incident coordinates are kept after closure for disputes and billing before they are removed. [F, compliance]
12. **Masked calling.** Needed for the pilot, or does the dispatcher relay contact. [F]
13. **Engagement.** Whether any of module E ships at launch; voucher economics and fraud rules. [E]
14. **Phone OTP and biometrics.** Whether either is needed given Clerk; SMS cost. [A]
15. **VIN data licence.** Which licensed source, and whether fitment verification for parts uses the same one. [A, parts planning]
16. **Consent purposes.** The list of purposes and versions to record, including marketing and insurer sharing. [B, compliance]

## 7. Sequence and decisions to record

Aligned with the HLD's delivery phases. Each step gets an architecture decision record in the style of [ADR 0014](adr/0014-customer-concierge.md) and [ADR 0015](adr/0015-hub-operations-settings.md) before code.

| Order | Work | HLD phase | ADR to write | Waits on |
|---|---|---|---|---|
| 1 | Push notifications and device registry | 2 | Customer notifications | — |
| 2 | Workshop Execution: check-in, stage events, handover; visit-status screen; stage photos | 2 | Workshop execution and visit status | decisions 4, 5, 6 |
| 3 | Scope change and shortfall approval, pay-now through hosted checkout | 2–3 | Scope change approval | decision 7; decision 8 only for one-tap pay |
| 4 | Cover Check, manual-first, administrator connectors as contracts allow | after 2 | Warranty cover check | decisions 1, 2, 3, 16 |
| 5 | Roadside: destination types, dispatch offers with cascade, partner page, partner accounts and dispatch fee, location retention | 3 | Partner dispatch offers and fees | decisions 9, 10, 11, 12 |
| 6 | Engagement: early-arrival voucher, API-served campaign configuration | after 3 | — | decision 13 |

Consent records (section 4, compliance) ride with step 1, since notifications are the first feature that needs one.

## 8. What to drop from the draft, and why

- **Native iOS and Android rewrites, Node.js or Go services, Redis, Celery or BullMQ, Socket.io.** The platform exists; the HLD rules out a second backend language at launch; none of these solves a measured problem.
- **Sub-second WebSockets.** Push plus a short visible-screen poll meets the need at pilot scale; HLD §7 defers WebSockets deliberately.
- **Live CCTV or 4K streaming and the R45 per day fee.** Privacy exposure for bystanders and staff, latency that is not live, cost outside the budget, and a payment capability that does not exist. Stage photos serve the purpose.
- **The general driver pool.** Unvetted partners are outside the HLD's approved-partner model; revisit after the contracted pilot.
- **The "hard-block" boolean.** Model it as readiness, which the platform already does for parts.
- **PostGIS and Redis Geo.** The haversine query serves one hub and tens of partners; add PostGIS when a measured query needs it and after its availability on RDS in Cape Town is confirmed, as the HLD notes.
- **Commercial figures inside the technical spec.** R45 per day, R250 per lead and 15% discounts are pricing decisions; record them in decisions 8, 10 and 13, not in the build specification.

## Appendix: where each draft section landed

| Draft section | Here |
|---|---|
| 1. Core tech stack and infrastructure | Section 2 |
| 2.A Authentication and vehicle garage | Section 4.A |
| 2.B T-48 pre-authorisation gate | Section 4.B, decisions 1–3 |
| 2.C Live tracking, countdown, streaming | Section 4.C, decisions 4–6 |
| 2.D Shortfall approval and hard block | Section 4.D, decisions 7–8 |
| 2.E Play button and engagement | Section 4.E, decision 13 |
| 2.F Towing dispatch marketplace | Section 4.F, decisions 9–12 |
| 3. Compliance, security and POPIA | Section 4 (compliance), section 5 item 7, decisions 11 and 16 |
