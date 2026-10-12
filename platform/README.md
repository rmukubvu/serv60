# Serv60 platform workspace

This directory bootstraps the first DDD vertical slice from the platform HLD.

## Included

- `backend/`: Java 25 + Spring Boot 4.1.1 modular monolith with booking, service planning, payment and inventory boundaries.
- `operations-web/`: Next.js 16.4 + TypeScript operations shell for the staff calendar.
- `mobile/`: Flutter customer app shell with booking and visit status flows.
- `infra/docker-compose.yml`: local PostgreSQL and LocalStack dependencies.
- `contracts/openapi.yaml`: the initial HTTP contract shared by web and mobile.

The backend is intentionally small but real: it exposes a health endpoint, hub availability, idempotent visit holds, current visit status, confirmation, cancellation, a bounded parts-readiness worklist for hub operations, and a provider-neutral payment order ledger. PostgreSQL migrations include stock balances, procurement demand, payment orders and a transactional outbox. Hosted checkout and signed webhook reconciliation are implemented behind the payment boundary; deployment credentials and provider URLs are required. See [`PAYMENTS.md`](PAYMENTS.md).

Authentication is an explicit deployment boundary. Set `SECURITY_ENABLED=true` and provide Spring's Cognito-compatible `spring.security.oauth2.resourceserver.jwt.issuer-uri` before exposing the API outside a trusted network. Local development and integration tests default to security disabled; this is not a production setting.

Google sign-in through Cognito, PKCE, callback configuration and the claims
used for customer and operator authorization are documented in
[`COGNITO_GOOGLE_SETUP.md`](COGNITO_GOOGLE_SETUP.md).

## Local backend

```sh
docker compose -f infra/docker-compose.yml up -d postgres
cd backend
mvn spring-boot:run
```

The bootstrap does not include `mvnw`; install Maven 3.9+ or generate the
wrapper once with `mvn -N wrapper:wrapper`. The API listens on
`http://localhost:8080`.

## Local operations UI

```sh
cd operations-web
npm install
npm run dev
```

Set `NEXT_PUBLIC_API_BASE_URL` when the API is not on `http://localhost:8080`.
For a deployed hub, also set `NEXT_PUBLIC_SERV60_HUB_ID` and the optional
`NEXT_PUBLIC_SERV60_CUSTOMER_ID` demo value. A ready-to-copy template is in
`operations-web/.env.example`.

## Mobile

Open `mobile/` with Flutter 3.35+ and run `flutter pub get && flutter run`. The customer shell uses Cognito Authorization Code + PKCE, bearer-token repositories, hosted Yoco checkout and secure payment deep-link recovery. Configure callbacks and native URL schemes using [`mobile/DEEP_LINK_SETUP.md`](mobile/DEEP_LINK_SETUP.md).

The mobile API and tenant scope are compile-time deployment settings. Android's
emulator reaches a host-local API through `10.0.2.2`; a physical device or a
deployed environment must use its reachable HTTPS endpoint:

```sh
cd mobile
flutter run \
  --dart-define=SERV60_API_BASE_URL=https://api.example.test \
  --dart-define=SERV60_HUB_ID=00000000-0000-0000-0000-000000000060 \
  --dart-define=SERV60_CUSTOMER_ID=00000000-0000-0000-0000-000000000001
```

These values are passed to the repositories through `String.fromEnvironment`
so local defaults remain convenient without hard-coding deployment identifiers
in booking requests. Cognito and payment configuration are also supplied with
`--dart-define`; no client secret or provider secret belongs in the app.

## Boundaries

The backend source is currently a runnable single Maven artifact with package-level DDD boundaries (`booking`, `serviceplanning`, and the runtime composition root). The next implementation step is splitting those package boundaries into the `-api`/`-impl` Maven artifacts described in the HLD; the HTTP contracts and database ownership rules are already explicit. The initial booking coordinator deliberately performs booking and stock reservation in one database transaction; extracting them into separate services requires the reservation saga described in the HLD.

## Complimentary visit options

The customer app always books `EXPRESS_OIL` for one hour. Customers may select
one free `BARBER` (45 minutes) or `PRIVATE_WORK` (60 minutes) option, or neither.
`complimentaryServiceCode` is optional on availability queries and hold requests.
Both resources start together and are allocated in one transaction under the
same hold. A conflict rolls back the entire hold; confirmation, cancellation and
expiry apply to both allocations. The option is included in the idempotency hash
and returned with the hold. Payment remains for the vehicle service only.
Existing standalone operator/API service requests remain compatible.

Deploy backend migration V11 before the updated mobile client. If combined
availability is empty or a hold returns 409, check the selected resource's active
flag, template duration and calendar before retrying. Monitor availability/hold
HTTP errors via the existing Actuator HTTP metrics during rollout. Roll back the
mobile release first if booking errors rise; the additive nullable column can
remain, and existing bundled holds must retain both allocations through expiry
or cancellation. No external provider calls are added to the transaction.

## Calendar booking details

Click a calendar booking to flip it and view the customer name, phone, car and
registration. Contact data is an optional immutable snapshot submitted in
`CreateHold.contact`; hub staff can capture it in Create appointment. Existing
bookings, including mobile bookings that do not yet capture contact, show
“Not provided”. The card fetches contact on demand through the operator-only,
hub-scoped appointment details endpoint, with `Cache-Control: no-store`.
Customer-facing hold/calendar responses do not include this contact snapshot.

Deploy V12 and the API before the operations UI; rollback the UI first and retain
the additive column. Monitor details endpoint 403/404/5xx responses through HTTP
metrics: verify operator role and hub membership for 403, booking/hub for 404,
and API availability for 5xx. No retry on authorization errors is automatic.
For the local seeded demo only, `infra/seed-appointment-details.sql` adds visibly
fictional contact/car details to the known demo customer's existing bookings.
The E2E seed script includes those details for new demo holds.
