# Serv60 platform workspace

This directory bootstraps the first DDD vertical slice from the platform HLD.

## Included

- `backend/`: Java 25 + Spring Boot 4.1.1 modular monolith with booking, service planning and inventory boundaries.
- `operations-web/`: Next.js 16.4 + TypeScript operations shell for the staff calendar.
- `mobile/`: Flutter customer app shell with booking and visit status flows.
- `infra/docker-compose.yml`: local PostgreSQL and LocalStack dependencies.
- `contracts/openapi.yaml`: the initial HTTP contract shared by web and mobile.

The backend is intentionally small but real: it exposes a health endpoint, hub availability, idempotent visit holds, current visit status, confirmation, cancellation, and a bounded parts-readiness worklist for hub operations. PostgreSQL migrations include stock balances, procurement demand, and a transactional outbox. Payment/provider adapters are represented by ports and remain disabled until credentials and contracts exist.

Authentication is an explicit deployment boundary. Set `SECURITY_ENABLED=true` and provide Spring's Cognito-compatible `spring.security.oauth2.resourceserver.jwt.issuer-uri` before exposing the API outside a trusted network. Local development and integration tests default to security disabled; this is not a production setting.

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

Open `mobile/` with Flutter 3.35+ and run `flutter pub get && flutter run`. The app deliberately uses an API repository boundary so authentication, native maps, payments and push can be added without putting domain calls in widgets.

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

These values are passed to `BookingRepository` through `String.fromEnvironment`
so local defaults remain convenient without hard-coding deployment identifiers
in booking requests. Authentication is the next boundary and will add a
short-lived bearer-token provider without changing the widget or repository
call sites.

## Boundaries

The backend source is currently a runnable single Maven artifact with package-level DDD boundaries (`booking`, `serviceplanning`, and the runtime composition root). The next implementation step is splitting those package boundaries into the `-api`/`-impl` Maven artifacts described in the HLD; the HTTP contracts and database ownership rules are already explicit. The initial booking coordinator deliberately performs booking and stock reservation in one database transaction; extracting them into separate services requires the reservation saga described in the HLD.
