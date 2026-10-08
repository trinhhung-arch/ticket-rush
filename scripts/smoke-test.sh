#!/usr/bin/env bash
# Walks the whole booking saga through the gateway: an organizer publishes an event, a customer
# holds seats, pays through the mock gateway and receives QR tickets by email; a second customer
# is declined and loses the hold. Then a waiting-room event and the booking rate limit. Needs curl and jq.
#   docker compose up -d --build && ./scripts/smoke-test.sh
set -euo pipefail

GATEWAY=${GATEWAY:-http://localhost:8080}
MAILPIT=${MAILPIT:-http://localhost:8025}
ORGANIZER=organizer-demo

uuid() { uuidgen 2>/dev/null | tr '[:upper:]' '[:lower:]' || cat /proc/sys/kernel/random/uuid; }
utc() { date -u -v"$1" +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -d "$2" +%Y-%m-%dT%H:%M:%SZ; }
step() { printf '\n\033[1m%s\033[0m\n' "$*"; }
fail() { printf '\033[31m%s\033[0m\n' "$*" >&2; exit 1; }

book() { # user idempotency-key seat...
  local user=$1 key=$2; shift 2
  local seats; seats=$(printf '"%s",' "$@"); seats="[${seats%,}]"
  curl -s -w '\nHTTP %{http_code}\n' -X POST "$GATEWAY/api/bookings" \
    -H "X-User-Id: $user" -H "Idempotency-Key: $key" -H 'Content-Type: application/json' \
    -d "{\"eventId\":\"$event_id\",\"seatCodes\":$seats,\"email\":\"$user@example.com\"}"
}
show() { jq -R 'fromjson? // .' | jq -c "if type == \"object\" then $1 else . end"; }
booking() { curl -sf "$GATEWAY/api/bookings/$2" -H "X-User-Id: $1"; }
wait_for_status() { # user booking-id status
  for _ in $(seq 1 30); do
    [ "$(booking "$1" "$2" | jq -r .status)" = "$3" ] && return 0
    sleep 1
  done
  fail "booking $2 never reached $3"
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
curl -sf -X POST "$GATEWAY/api/events/$event_id/publish" -H "X-User-Id: $ORGANIZER" | jq -c '{status, totalSeats}'

step "3. booking-service builds the seat map from the Kafka message (FR-BKG-01)"
for _ in $(seq 1 30); do
  curl -sf "$GATEWAY/api/events/$event_id/seats" > /dev/null && break
  sleep 1
done
curl -sf "$GATEWAY/api/events/$event_id/seats" | jq -c '{total, available, held, sold}'

step "4. Alice holds VIP-A-01 and VIP-A-02 (FR-BKG-02)"
alice_key=$(uuid)
alice_booking=$(book alice "$alice_key" VIP-A-01 VIP-A-02 | head -1 | jq -r .id)
booking alice "$alice_booking" | jq -c '{id, status, totalVnd, expiresAt}'

step "5. Her app retries with the same Idempotency-Key: same booking, HTTP 200 (FR-BKG-04)"
book alice "$alice_key" VIP-A-01 VIP-A-02 | show '{id, status}'

step "6. Bob wants VIP-A-02 and VIP-A-03: 409, and VIP-A-03 is not held either (FR-BKG-02)"
book bob "$(uuid)" VIP-A-02 VIP-A-03 | show '{status, detail, seatCode}'

step "7. payment-service opens a payment and the booking waits for it (FR-PAY-01)"
wait_for_status alice "$alice_booking" AWAITING_PAYMENT
checkout_url=$(booking alice "$alice_booking" | jq -r .checkoutUrl)
booking alice "$alice_booking" | jq -c '{status, checkoutUrl}'

step "8. Alice pays through the mock gateway, which calls the webhook (FR-PAY-02)"
curl -sf -X POST "$checkout_url" -H 'Content-Type: application/json' -d '{"outcome":"SUCCEEDED"}' \
  | jq -c '{transactionId, status: .payment.status}'

step "9. The saga confirms the booking and sells the seats (FR-BKG-09)"
wait_for_status alice "$alice_booking" CONFIRMED
booking alice "$alice_booking" | jq -c '{status, seats: [.seats[].seatCode]}'
curl -sf "$GATEWAY/api/events/$event_id/seats" | jq -c '{available, held, sold}'

step "10. ticket-service issues one signed QR ticket per seat (FR-TKT-01, FR-TKT-02)"
for _ in $(seq 1 30); do
  [ "$(curl -sf "$GATEWAY/api/tickets?bookingId=$alice_booking" -H 'X-User-Id: alice' | jq length)" = 2 ] && break
  sleep 1
done
curl -sf "$GATEWAY/api/tickets?bookingId=$alice_booking" -H 'X-User-Id: alice' \
  | jq -c '[.[] | {seatCode, qrToken: (.qrToken[0:20] + "...")}]'

step "11. notification-service emails the tickets (FR-NTF-01); open $MAILPIT to see them"
for _ in $(seq 1 30); do
  subject=$(curl -sf "$MAILPIT/api/v1/messages" \
    | jq -r --arg to "alice@example.com" '[.messages[] | select(any(.To[]; .Address == $to))][0].Subject // empty')
  [ -n "$subject" ] && break
  sleep 1
done
[ -n "$subject" ] || fail "no ticket email for alice"
echo "email: $subject"

step "12. Chi holds VIP-B-01 but the card is declined: booking cancelled, seat free again"
chi_booking=$(book chi "$(uuid)" VIP-B-01 | head -1 | jq -r .id)
wait_for_status chi "$chi_booking" AWAITING_PAYMENT
curl -sf -X POST "$(booking chi "$chi_booking" | jq -r .checkoutUrl)" -H 'Content-Type: application/json' \
  -d '{"outcome":"DECLINED"}' | jq -c '{status: .payment.status}'
wait_for_status chi "$chi_booking" CANCELLED
booking chi "$chi_booking" | jq -c '{status, cancelReason}'
sleep 1
curl -sf "$GATEWAY/api/events/$event_id/seats" | jq -c '[.seats[] | select(.code == "VIP-B-01") | {code, state}][0]'

step "13. A sell-out event uses the waiting room; booking without an admission token is refused (FR-WR-03)"
hot_event=$(curl -sf -X POST "$GATEWAY/api/events" -H "X-User-Id: $ORGANIZER" -H 'Content-Type: application/json' -d @- <<JSON | jq -r .id
{
  "name": "TicketRush Live: đêm cuối",
  "venue": "Sân vận động Mỹ Đình",
  "city": "Hanoi",
  "startsAt": "$(utc +31d '+31 days')",
  "salesOpenAt": "$(utc -1H '-1 hour')",
  "waitingRoom": true,
  "sections": [{"code": "GA", "name": "Standard", "rows": 5, "seatsPerRow": 20, "priceVnd": 800000}]
}
JSON
)
curl -sf -X POST "$GATEWAY/api/events/$hot_event/publish" -H "X-User-Id: $ORGANIZER" > /dev/null
for _ in $(seq 1 30); do
  curl -sf "$GATEWAY/api/events/$hot_event/seats" > /dev/null && break
  sleep 1
done
book_hot() { # user [admission-token]
  curl -s -w '\nHTTP %{http_code}\n' -X POST "$GATEWAY/api/bookings" \
    -H "X-User-Id: $1" -H "Idempotency-Key: $(uuid)" -H "X-Admission-Token: ${2:-}" -H 'Content-Type: application/json' \
    -d "{\"eventId\":\"$hot_event\",\"seatCodes\":[\"GA-A-01\"],\"email\":\"$1@example.com\"}"
}
book_hot dung | show '{status, detail, joinQueue}'

step "14. Dung joins the queue; the room has space, so he is admitted with a token (FR-WR-01)"
token=$(curl -sf -X POST "$GATEWAY/api/queue/events/$hot_event/join" -H 'X-User-Id: dung' | tee /tmp/ticketrush-queue.json | jq -r .admissionToken)
jq -c '{state, position, admittedUntil, admissionToken: (.admissionToken[0:24] + "...")}' /tmp/ticketrush-queue.json

step "15. With the token his booking goes through"
book_hot dung "$token" | show '{id, status}'

step "16. A bot fires 20 booking requests at once: the gateway lets about 10 through per second (FR-GW-02)"
codes=$(for _ in $(seq 1 20); do
  curl -s -o /dev/null -w '%{http_code}\n' -X POST "$GATEWAY/api/bookings" -H 'X-User-Id: bot' \
    -H "Idempotency-Key: $(uuid)" -H 'Content-Type: application/json' -d '{}' &
done; wait)
echo "$codes" | sort | uniq -c | awk '{printf "  %s x HTTP %s\n", $1, $2}'
echo "$codes" | grep -q 429 || fail "no request was rate limited"

printf '\n\033[32mAll steps passed.\033[0m\n'
