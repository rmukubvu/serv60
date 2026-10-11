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
./mvnw spring-boot:run
```

The repository currently does not include a Maven wrapper because Maven is not
available in the bootstrap environment. Install Maven 3.9+ or generate the
wrapper once with `mvn -N wrapper:wrapper` before using `./mvnw`.

The API is intentionally local-only at this stage. Authentication, payment,
provider scheduling, and the outbox publisher are explicit follow-on slices;
they must be added before exposing the service outside a trusted network.

With Floci running on `http://localhost:4566`, validate the SQS boundary with
`bash ../scripts/floci-sqs-smoke.sh` from this directory. Set
`SERV60_AWS_ENDPOINT` or `SERV60_SQS_QUEUE_NAME` to override the defaults.
