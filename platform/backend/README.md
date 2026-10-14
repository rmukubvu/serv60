# Serv60 backend

This is the first deployable vertical slice of the Serv60 platform. It is a
Spring Boot 4.1.1 modular monolith on Java 25, with package boundaries that
mirror the DDD bounded contexts in the HLD. Booking, resource allocation,
stock reservation, parts demand, and the transactional outbox are persisted in
PostgreSQL.

## Run locally

From `platform/`:

```bash
docker compose -f infra/docker-compose.yml up -d postgres localstack
cd backend
SECURITY_ENABLED=false ./mvnw spring-boot:run
```

Security is on by default. With it enabled, startup fails unless
`SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI` (or the JWK set URI) is
set. Compose and the tests switch it off explicitly; never deploy with
`SECURITY_ENABLED=false`.

The repository currently does not include a Maven wrapper because Maven is not
available in the bootstrap environment. Install Maven 3.9+ or generate the
wrapper once with `mvn -N wrapper:wrapper` before using `./mvnw`.

The API is intentionally local-only at this stage. Authentication, payment,
provider scheduling, and the outbox publisher are explicit follow-on slices;
they must be added before exposing the service outside a trusted network.

With Floci running on `http://localhost:4566`, validate the SQS boundary with
`bash ../scripts/floci-sqs-smoke.sh` from this directory. Set
`SERV60_AWS_ENDPOINT` or `SERV60_SQS_QUEUE_NAME` to override the defaults.

## Seeded end-to-end demo

From `platform/`, run the repeatable demo against PostgreSQL and the
containerized API:

```bash
bash scripts/e2e-seeded-demo.sh
```

Flyway seeds the pilot hub, services, bays, workspace, barber chair and stock.
The script proves availability, creates and confirms an express oil hold,
checks reserved parts and verifies the confirmed calendar allocation. The
containers remain running so the operations UI and mobile app can be pointed at
the same seeded data.
