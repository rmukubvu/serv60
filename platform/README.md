# Serv60 platform workspace

This directory bootstraps the first DDD vertical slice from the platform HLD.

## Included

- `backend/`: Java 25 + Spring Boot 4.1.1 modular monolith with booking, service planning and inventory boundaries.
- `operations-web/`: Next.js 16.4 + TypeScript operations shell for the staff calendar.
- `mobile/`: Flutter customer app shell with booking and visit status flows.
- `infra/docker-compose.yml`: local PostgreSQL and LocalStack dependencies.
- `contracts/openapi.yaml`: the initial HTTP contract shared by web and mobile.

The backend is intentionally small but real: it exposes a health endpoint, hub availability, idempotent visit holds, and confirmation. PostgreSQL migrations include stock balances, procurement demand, and a transactional outbox. Payment/provider adapters are represented by ports and remain disabled until credentials and contracts exist.

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

## Mobile

Open `mobile/` with Flutter 3.35+ and run `flutter pub get && flutter run`. The app deliberately uses an API repository boundary so authentication, native maps, payments and push can be added without putting domain calls in widgets.

## Boundaries

The backend source is currently a runnable single Maven artifact with package-level DDD boundaries (`booking`, `serviceplanning`, and the runtime composition root). The next implementation step is splitting those package boundaries into the `-api`/`-impl` Maven artifacts described in the HLD; the HTTP contracts and database ownership rules are already explicit. The initial booking coordinator deliberately performs booking and stock reservation in one database transaction; extracting them into separate services requires the reservation saga described in the HLD.
