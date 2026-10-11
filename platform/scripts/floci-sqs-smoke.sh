#!/usr/bin/env bash
set -euo pipefail

endpoint="${SERV60_AWS_ENDPOINT:-http://localhost:4566}"
queue_name="${SERV60_SQS_QUEUE_NAME:-serv60-events-smoke}"
region="${AWS_DEFAULT_REGION:-af-south-1}"
export AWS_ACCESS_KEY_ID="${AWS_ACCESS_KEY_ID:-test}"
export AWS_SECRET_ACCESS_KEY="${AWS_SECRET_ACCESS_KEY:-test}"
export AWS_DEFAULT_REGION="$region"

queue_url="$(aws --endpoint-url "$endpoint" sqs create-queue --queue-name "$queue_name" --query QueueUrl --output text)"
body='{"eventType":"FlociSmoke","aggregateId":"00000000-0000-0000-0000-000000000001"}'
aws --endpoint-url "$endpoint" sqs send-message --queue-url "$queue_url" --message-body "$body" \
  --message-attributes eventType='{DataType=String,StringValue=FlociSmoke}' >/dev/null

received="$(aws --endpoint-url "$endpoint" sqs receive-message --queue-url "$queue_url" \
  --message-attribute-names All --wait-time-seconds 2 --query 'Messages[0].Body' --output text)"
if [[ "$received" != "$body" ]]; then
  echo "Expected Floci SQS message '$body' but received '$received'" >&2
  exit 1
fi
echo "Floci SQS smoke test passed: $queue_url"
