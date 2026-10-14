#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")" && pwd)"
compose="${SERV60_COMPOSE_FILE:-$root/docker-compose.yml}"
api_base="${SERV60_API_BASE_URL:-http://localhost:8080}"
hub_id="00000000-0000-0000-0000-000000000060"

echo "Seeding ops insights demo data…"
docker compose -f "$compose" exec -T postgres \
  psql -U serv60 -d serv60 < "$root/seed-ops-insights.sql"

echo "Checking insights API…"
curl --fail --silent "$api_base/v1/hubs/$hub_id/ops-insights" | jq '{
  alertCount: (.alerts | length),
  alerts: [.alerts[] | {severity, code, title}],
  dispatchCount: (.dispatchPriority | length),
  topDispatch: .dispatchPriority[0]
}'

echo
echo "Open http://localhost:3000 and click Insights (or scroll under the hub metrics)."
echo "Roadside ranking is under Roadside dispatch on the same overview page."
