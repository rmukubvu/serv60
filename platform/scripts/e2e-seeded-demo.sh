#!/usr/bin/env bash
set -euo pipefail

compose_file="${SERV60_COMPOSE_FILE:-$(cd "$(dirname "$0")/../infra" && pwd)/docker-compose.yml}"
api_base="${SERV60_API_BASE_URL:-http://localhost:8080}"
hub_id="00000000-0000-0000-0000-000000000060"
customer_id="00000000-0000-0000-0000-000000000001"
vehicle_id="00000000-0000-0000-0000-000000000010"
now="$(date -u +%Y-%m-%dT%H:%M:00Z)"
end="$(date -u -v+60M +%Y-%m-%dT%H:%M:00Z 2>/dev/null || date -u -d '+60 minutes' +%Y-%m-%dT%H:%M:00Z)"
idempotency_key="seeded-demo-${now//[^0-9]/}"

echo "Starting the seeded Serv60 demo stack (reusing Floci on port 4566)..."
docker compose -f "$compose_file" up -d --build postgres backend

for attempt in $(seq 1 60); do
  if curl --fail --silent "$api_base/actuator/health" >/dev/null; then
    break
  fi
  if [[ "$attempt" == 60 ]]; then
    echo "Backend did not become ready at $api_base" >&2
    docker compose -f "$compose_file" logs --tail=80 backend >&2
    exit 1
  fi
  sleep 2
done

echo "Checking seeded availability..."
availability="$(curl --fail --silent --get "$api_base/v1/hubs/$hub_id/availability" \
  --data-urlencode 'serviceCode=EXPRESS_OIL' \
  --data-urlencode "from=$now" \
  --data-urlencode "to=$end")"
echo "$availability" | jq --exit-status 'length > 0 and .[0].serviceCode == "EXPRESS_OIL"' >/dev/null
echo "$availability" | jq '.[0]'

echo "Creating a seeded customer hold..."
hold="$(curl --fail --silent --request POST "$api_base/v1/visit-holds" \
  --header 'Content-Type: application/json' \
  --header "Idempotency-Key: $idempotency_key" \
  --data "$(jq -n --arg hub "$hub_id" --arg customer "$customer_id" --arg vehicle "$vehicle_id" --arg start "$now" --arg end "$end" '{hubId:$hub,customerId:$customer,vehicleId:$vehicle,serviceCode:"EXPRESS_OIL",startsAt:$start,endsAt:$end,policy:"READY_STOCK"}')")"
hold_id="$(echo "$hold" | jq -r '.id')"
echo "$hold" | jq '{id,status,serviceCode,policy,startsAt,endsAt}'

echo "Confirming the hold and reading the hub worklist..."
confirmed="$(curl --fail --silent --request POST "$api_base/v1/visit-holds/$hold_id/confirm")"
echo "$confirmed" | jq --exit-status '.status == "CONFIRMED"' >/dev/null
echo "$confirmed" | jq '{id,status,serviceCode,policy}'

readiness="$(curl --fail --silent "$api_base/v1/hubs/$hub_id/parts-readiness?limit=50")"
echo "$readiness" | jq --exit-status 'map(select(.sku == "OIL-5W30"))[0].reservedQuantity >= 1' >/dev/null
echo "$readiness" | jq 'map(select(.sku | startswith("OIL") or startswith("FILTER")))'

calendar="$(curl --fail --silent --get "$api_base/v1/hubs/$hub_id/calendar" \
  --data-urlencode "from=$now" \
  --data-urlencode "to=$end")"
echo "$calendar" | jq --exit-status --arg hold "$hold_id" 'map(select(.holdId == $hold)) | length == 1 and .[0].status == "CONFIRMED"' >/dev/null

echo
echo "Seeded Serv60 E2E demo passed."
echo "Hold: $hold_id"
echo "Operations UI: http://localhost:3000"
echo "API: $api_base"
echo "The stack remains running so the mobile and operations flows can be opened against seeded data."
