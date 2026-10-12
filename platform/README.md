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
