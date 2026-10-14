# Customer journey: development and operations

The mobile app now starts with Explore, followed by Assistant, Bookings and Roadside. Nearby discovery returns published hubs within 20 km, ordered by straight-line distance. Each hub advertises only active complimentary templates with an active matching resource. Readiness checks every required SKU separately. Ready stock is reserved atomically; shortages create procurement demand, and retries preserve the original readiness policy. Every customer visit reserves EXPRESS_OIL for one hour and zero or one complimentary option. South African hub times display in SAST (UTC+2). This release assumes all published hubs operate in South Africa; international hubs need timezone-aware presentation before publishing.

## Local demo

Start PostgreSQL and the backend using the existing Compose stack. Put credentials in `platform/infra/.env.local` (Git-ignored, permissions `0600`):

```dotenv
OPENROUTER_API_KEY=<development key>
SERV60_ASSISTANT_MODEL=openai/gpt-4.1-mini
```

Do not place provider credentials in Flutter defines, browser code, Docker build arguments, screenshots or committed examples. Production credentials should be injected from AWS Secrets Manager. The configured model was tested with live OpenRouter tool calls on 2026-10-12.

```sh
docker compose --env-file platform/infra/.env.local -f platform/infra/docker-compose.yml up -d --build --no-deps backend
docker compose -f platform/infra/docker-compose.yml exec -T postgres psql -U serv60 -d serv60 -v ON_ERROR_STOP=1 < platform/infra/seed-customer-discovery.sql
docker compose -f platform/infra/docker-compose.yml exec -T postgres psql -U serv60 -d serv60 -v ON_ERROR_STOP=1 < platform/infra/seed-roadside-partners.sql
```

Migrations ship no tow partners. The roadside demo fleet comes only from `seed-roadside-partners.sql`; never run it against a shared database.

The seed is idempotent and explicitly illustrative: City Bowl (barber/workspace), Sea Point (barber/massage), Claremont (workspace). It does not claim these are operating locations. The mobile demo build enables `SERV60_DEMO_MODE=true`; production defaults to false. Selecting Explore Cape Town demo uses illustrative coordinates without requesting device location. Foreground location requires a deliberate button press. Search filters the returned hub names, suburbs and service labels; it is not address geocoding or driving-route estimation.

Blank OpenRouter key or model disables the assistant while manual booking remains available. Blank Yoco configuration leaves reservations payable but cannot open hosted checkout. Local Compose uses an illustrative R249 car-service price; production `SERV60_EXPRESS_PRICE_MINOR` defaults to zero, disabling the new payment endpoint until pricing is configured. Complimentary options never add to that price. A proposal confirmation reserves a hold, not a paid appointment; it lasts `HOLD_DURATION_SECONDS` (default fifteen minutes, range one minute to one hour). The customer completes hosted Yoco checkout; only an authoritative PAID order permits the customer confirmation endpoint. Legacy `/v1/visit-holds` create/confirm/cancel commands now explicitly require an operator role, so customer callers cannot bypass mandatory servicing, payment or the change policy. Upgrade customer clients to `/v1/customer` before enabling security. Existing operator confirmation remains available for local fixtures/offline hub workflows.

## Contracts and domain boundaries

- `GET /v1/customer/hubs?latitude=...&longitude=...&q=...`: bounded published directory, 20 km maximum, at most 50 results.
- `GET /v1/customer/hubs/{hubId}` and `/slots?date=YYYY-MM-DD&complimentaryServiceCode=...&replacing=...`: real bay and complimentary capacity; opening hours and a 90-day horizon. Replacement searches exclude the owned original allocation and enforce 48 hours notice.
- `GET /v1/customer/appointments?limit=20&after=...&afterId=...`: upcoming owned confirmed appointments or unexpired holds; keyset pagination, maximum 50.
- `POST /v1/customer/appointments`: explicit contact/car details, server-owned customer identity, mandatory car service; `Idempotency-Key` required.
- `PUT /v1/customer/appointments/{id}`: date/time and complimentary option plus expected revision and `Idempotency-Key`. Same-key retries replay the original result; changed payloads/stale revisions conflict.
- `POST /v1/customer/appointments/{id}/payment`: server-priced payment order, deterministic key; no client-supplied identity or amount.
- `PUT /v1/customer/appointments/{id}/confirm-payment`: verifies ownership and PAID state before confirmation.
- `POST /v1/customer/hubs/{hubId}/assistant`: at most 16 customer/assistant turns, 2,000 characters each; arbitrary roles/tool responses rejected.
- `PUT /v1/customer/assistant/proposals/{id}/confirm`: scoped immutable proposal, ten-minute expiry, serialized confirmation and stable booking key. Repeated confirmation returns the same owned booking.

Booking, hubs and payments expose named public API interfaces. Concierge depends on those interfaces, not internal implementations. Module-cycle checks run in CI. JDBC transactions own resource/parts changes and outbox writes. Hosted checkout now runs outside database transactions, checks reservation expiry, and uses a stable provider idempotency key per payment order. New customer confirmation checks the paid amount and currency against the configured server price. No provider HTTP call runs inside a database transaction. Cognito identity comes from verified `customer_id`, `custom:customer_id`, or UUID subject. Security-disabled development uses a fixed demo UUID; never deploy with security disabled.

The 48-hour cutoff is inclusive: a confirmed appointment exactly 48 hours away may be changed. Both original and replacement start must satisfy the rule. Old capacity is released and replacement capacity acquired atomically; failed allocation rolls back. Payment and parts associations remain attached to the same appointment. Concurrent changes at one revision have a single winner. `VisitRescheduled` is appended to the outbox in the same transaction. Customer changes currently cover time/date and the complimentary option; switching hubs, vehicle/contact amendments and cancellations are not exposed in this journey.

## Failure controls and operation

The provider adapter uses a three-second connect timeout, twelve-second request timeout, no redirects or automatic retries, and a 128 KB response cap. Each turn allows at most three model calls, four sequential tools per response and 700 output tokens per model call, stopping at the first proposal. `SERV60_ASSISTANT_CONCURRENT_TURNS` defaults to four (range 1–16); this is a conservative pilot limit, to be load-tested before increasing. The Flutter request timeout is 45 seconds for assistant turns and 15 seconds for other commands. Any reverse proxy must allow at least 45 seconds for the assistant route. No streaming or background agent execution is claimed.

A database quota permits 30 assistant turns per customer per UTC hour across instances. Quota/overload/provider failures return 503 with Retry-After 30 and readable manual-booking guidance. Set an OpenRouter project/key spend cap before production. The server's allowlist executes only availability reads, owned-booking reads and proposal preparation; the model never directly executes reservations, arbitrary URLs/SQL or payments. All proposal details are validated again at confirmation.

OpenRouter routing uses `require_parameters=true` and `data_collection=deny`. This model's endpoints do not advertise `parallel_tool_calls`; that unsupported request parameter is omitted. Batched tool requests are handled sequentially within the server's four-tool cap. Disabling privacy constraints is not an error fallback. The selected provider still needs a production privacy and data-processing review: customer chat can contain contact details. Location is not included in model context. Do not enable request-body or authentication-header logging. Redact discovery coordinates from ingress/access logs as well as application telemetry. No chat history is persisted server-side; proposal payloads contain visit contacts. Traffic-triggered bounded cleanup removes proposal rows over 30 days past expiry and quota rows over two days old, up to 100 of each per request. For a quiet deployment, schedule equivalent bounded maintenance; this release does not claim a wall-clock deletion SLA.

Metrics:

- `http.server.requests`: route latency/status for customer APIs.
- `serv60.assistant.turn.duration`: whole conversation turn time.
- `serv60.assistant.provider.duration` and `.requests`, tagged by success/error.
- `serv60.assistant.provider.responses`, tagged by HTTP status, without provider bodies or credentials.

Initial alarm assumptions: alert when assistant provider errors exceed 10% with at least 20 requests over five minutes, or customer booking 5xx exceeds 1% with at least 20 requests. These are proposed dashboard/alarm thresholds, not deployed CloudWatch alarms in this slice. A 401 suggests a revoked key; 404 often indicates model/parameter routing; 429 indicates provider quota; repeated timeouts/5xx indicate dependency trouble. Check metrics and provider status, rotate/configure the secret or model, or blank the model setting to disable AI while preserving manual booking. Stale revision/slot conflicts need a client refresh, never a blind mutation retry.

Roll out migrations V13–V15 before application instances. Publish verified coordinates, resources and opening hours for one hub; canary the customer journey; then add hubs. Rollback disables the assistant independently or reverts application code while leaving additive tables intact. Test coverage gates enforce 80% per Java class and per Flutter source file. Flutter tests use one worker to avoid observed local coverage-collector crashes under parallel execution. The application lockfile is committed for reproducible CI resolution.

## Native packaging prerequisites

This repository still does not contain generated Android/iOS platform shells. Before shipping native builds, generate them with the existing Flutter workflow, configure the previously documented Cognito/payment return schemes, and add:

- Android manifest: `android.permission.ACCESS_COARSE_LOCATION` (or FINE only if the product later needs precise location). No background location permission.
- iOS Info.plist: `NSLocationWhenInUseUsageDescription` with “Find Serv60 hubs within 20 km of your location.” Follow Geolocator's current iOS build setup to bypass always-location permission when only foreground location is used.
- Web: serve over HTTPS (localhost is allowed in development); handle denied/location-disabled states as shown by the tested adapter.

References: [Geolocator setup](https://pub.dev/packages/geolocator), [OpenRouter tool calling](https://openrouter.ai/docs/guides/features/tool-calling), [provider routing](https://openrouter.ai/docs/guides/routing/provider-selection).

Canary watch: observe one staffed hub for at least one hour and 20 assistant requests before expanding. Disable the model on sustained dependency errors above the assumed threshold; revert the application on booking integrity failures. Late payments received after a hold expires still require operator reconciliation/refund rather than silently reserving a different slot; native credentials and that operational Yoco process must be verified before taking live payments.

## Payment settlement

A verified Yoco webhook that moves an order to PAID confirms the customer's hold in the same database transaction. This holds even when the hold is past its displayed expiry, as long as the expiry sweep has not yet released its bay and parts. The customer `confirm-payment` call is now only a safe re-read.

When a settled payment cannot confirm its hold, the order stays PAID, the hold is left unchanged, and the platform appends a `PaidVisitNeedsReconciliation` outbox event with the reason. The usual causes are a hold already released by expiry or cancellation, or an amount that differs from the configured express price.

- **Metric:** `serv60.payment.settlement`, tagged `outcome` (`confirmed`, `already_confirmed`, `amount_mismatch`, `hold_expired`, `hold_cancelled`, `hold_missing`).
- **Alarm (assumed threshold):** any non-confirmed outcome other than `already_confirmed` in five minutes. Every one is a customer who paid without a booking.
- **Respond:** find the order from the outbox payload, contact the customer, and either book a new slot manually or refund in the Yoco dashboard. Never re-open the released hold by editing rows.
