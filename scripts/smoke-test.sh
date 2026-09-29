#!/usr/bin/env bash
# Walks through phase 1 via the gateway: create and publish an event, hold seats, retry safely,
# and show that a second customer cannot take a held seat. Needs curl and jq.
#   docker compose up -d --build && ./scripts/smoke-test.sh
set -euo pipefail

GATEWAY=${GATEWAY:-http://localhost:8080}
ORGANIZER=organizer-demo

uuid() { uuidgen 2>/dev/null | tr '[:upper:]' '[:lower:]' || cat /proc/sys/kernel/random/uuid; }
utc() { date -u -v"$1" +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -d "$2" +%Y-%m-%dT%H:%M:%SZ; }
step() { printf '\n\033[1m%s\033[0m\n' "$*"; }

book() { # user idempotency-key seat...
  local user=$1 key=$2; shift 2
  local seats; seats=$(printf '"%s",' "$@"); seats="[${seats%,}]"
  curl -s -w '\nHTTP %{http_code}\n' -X POST "$GATEWAY/api/bookings" \
    -H "X-User-Id: $user" -H "Idempotency-Key: $key" -H 'Content-Type: application/json' \
    -d "{\"eventId\":\"$event_id\",\"seatCodes\":$seats,\"email\":\"$user@example.com\"}"
}

step "1. Organizer creates a draft event (FR-EVT-01)"
event_id=$(curl -sf -X POST "$GATEWAY/api/events" -H "X-User-Id: $ORGANIZER" -H 'Content-Type: application/json' -d @- <<JSON | jq -r .id
{
  "name": "TicketRush Live",
  "venue": "Sân vận động Mỹ Đình",
  "city": "Hanoi",
  "startsAt": "$(utc +30d '+30 days')",
  "salesOpenAt": "$(utc -1H '-1 hour')",
  "sections": [
    {"code": "VIP", "name": "VIP", "rows": 2, "seatsPerRow": 10, "priceVnd": 3000000},
    {"code": "GA", "name": "Standard", "rows": 10, "seatsPerRow": 20, "priceVnd": 800000}
  ]
}
JSON
)
echo "event $event_id"

step "2. Organizer publishes it; EventPublished goes out through the outbox (FR-EVT-02)"
curl -sf -X POST "$GATEWAY/api/events/$event_id/publish" -H "X-User-Id: $ORGANIZER" | jq '{status, totalSeats}'

step "3. booking-service builds the seat map from the Kafka message (FR-BKG-01)"
for _ in $(seq 1 30); do
  curl -sf "$GATEWAY/api/events/$event_id/seats" > /dev/null && break
  sleep 1
done
curl -sf "$GATEWAY/api/events/$event_id/seats" | jq '{total, available, held, sold}'

step "4. Alice holds VIP-A-01 and VIP-A-02 (FR-BKG-02)"
alice_key=$(uuid)
book alice "$alice_key" VIP-A-01 VIP-A-02 | jq -R 'fromjson? // .' | jq -c 'if type == "object" then {id, status, totalVnd, expiresAt} else . end'

step "5. Her app retries with the same Idempotency-Key: same booking, HTTP 200 (FR-BKG-04)"
book alice "$alice_key" VIP-A-01 VIP-A-02 | jq -R 'fromjson? // .' | jq -c 'if type == "object" then {id, status} else . end'

step "6. Bob wants VIP-A-02 and VIP-A-03: 409, and VIP-A-03 is not held either (FR-BKG-02)"
book bob "$(uuid)" VIP-A-02 VIP-A-03 | jq -R 'fromjson? // .' | jq -c 'if type == "object" then {status, detail, seatCode} else . end'

step "7. Seat map now shows 2 held seats"
curl -sf "$GATEWAY/api/events/$event_id/seats" \
  | jq '{total, available, held, sold, vipRowA: [.seats[] | select(.code | startswith("VIP-A-0")) | {code, state}][0:4]}'
