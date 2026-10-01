#!/usr/bin/env bash
# Walks the whole booking saga through the gateway with Keycloak accounts: an organizer publishes an
# event, a customer holds seats, pays through the mock gateway, receives QR tickets by email and is
# checked in at the door; a second customer is declined, loses the hold and is told why by email.
# Then a waiting-room event and the booking rate limit. Needs curl, jq and openssl.
#   docker compose up -d --build && ./scripts/smoke-test.sh
set -euo pipefail
cd "$(dirname "$0")/.."
# Values passed in (e.g. the kind cluster's demo password) win over .env.
from_caller=${DEMO_USER_PASSWORD:-}
if [ -f .env ]; then set -a; . ./.env; set +a; fi
if [ -n "$from_caller" ]; then DEMO_USER_PASSWORD=$from_caller; fi
if [ -z "${DEMO_USER_PASSWORD:-}" ]; then
  echo "DEMO_USER_PASSWORD is not set; pass it in or run ./scripts/init-dev-env.sh" >&2; exit 1
fi

GATEWAY=${GATEWAY:-http://localhost:${GATEWAY_PORT:-8080}}
KEYCLOAK=${KEYCLOAK:-http://localhost:8180}
MAILPIT=${MAILPIT:-http://localhost:8025}

uuid() { uuidgen 2>/dev/null | tr '[:upper:]' '[:lower:]' || cat /proc/sys/kernel/random/uuid; }
utc() { date -u -v"$1" +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -d "$2" +%Y-%m-%dT%H:%M:%SZ; }
step() { printf '\n\033[1m%s\033[0m\n' "$*"; }
fail() { printf '\033[31m%s\033[0m\n' "$*" >&2; exit 1; }

# Signs in a demo account (password grant on the dev-only ticketrush-cli client) and keeps its token.
login() { # name
  local token
  token=$(curl -sf "$KEYCLOAK/realms/ticketrush/protocol/openid-connect/token" -d grant_type=password \
    -d client_id=ticketrush-cli -d "username=$1@ticketrush.dev" --data-urlencode "password=$DEMO_USER_PASSWORD" \
    | jq -r .access_token) || fail "could not sign in as $1@ticketrush.dev; is Keycloak up on $KEYCLOAK?"
  eval "TOKEN_$1=\$token"
}
auth() { local var="TOKEN_$1"; printf 'Authorization: Bearer %s' "${!var}"; }

book() { # user idempotency-key seat...
  local user=$1 key=$2; shift 2
  local seats; seats=$(printf '"%s",' "$@"); seats="[${seats%,}]"
  curl -s -w '\nHTTP %{http_code}\n' -X POST "$GATEWAY/api/bookings" \
    -H "$(auth "$user")" -H "Idempotency-Key: $key" -H 'Content-Type: application/json' \
    -d "{\"eventId\":\"$event_id\",\"seatCodes\":$seats}"
}
show() { jq -R 'fromjson? // .' | jq -c "if type == \"object\" then $1 else . end"; }
booking() { curl -sf "$GATEWAY/api/bookings/$2" -H "$(auth "$1")"; }
emails_to() { # address subject
  curl -sf "$MAILPIT/api/v1/messages" \
    | jq -r --arg to "$1" --arg subject "$2" '[.messages[] | select(any(.To[]; .Address == $to) and .Subject == $subject)] | length'
}
wait_for_status() { # user booking-id status
  for _ in $(seq 1 30); do
    [ "$(booking "$1" "$2" | jq -r .status)" = "$3" ] && return 0
    sleep 1
  done
  fail "booking $2 never reached $3"
}

step "0. Everyone signs in through Keycloak; calls without a token or with the wrong role are refused (FR-IAM-01)"
for user in organizer alice bob chi dung; do login "$user"; done
echo "signed in: organizer, alice, bob, chi, dung"
printf 'no token, POST /api/bookings:         '
curl -s -o /dev/null -w '%{http_code}\n' -X POST "$GATEWAY/api/bookings" -H 'Content-Type: application/json' -d '{}'
printf 'customer alice, POST /api/events:     '
curl -s -o /dev/null -w '%{http_code}\n' -X POST "$GATEWAY/api/events" -H "$(auth alice)" -H 'Content-Type: application/json' -d '{}'

step "1. Organizer creates a draft event (FR-EVT-01)"
event_id=$(curl -sf -X POST "$GATEWAY/api/events" -H "$(auth organizer)" -H 'Content-Type: application/json' -d @- <<JSON | jq -r .id
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
curl -sf -X POST "$GATEWAY/api/events/$event_id/publish" -H "$(auth organizer)" | jq -c '{status, totalSeats}'

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

step "8. Alice pays through the mock gateway, which sends a signed webhook (FR-PAY-02, FR-PAY-04)"
payment_id=$(echo "$checkout_url" | sed -E 's#.*/payments/([^/]+)/checkout#\1#')
forged="{\"paymentId\":\"$payment_id\",\"transactionId\":\"forged\",\"outcome\":\"SUCCEEDED\"}"
printf 'unsigned webhook claiming success:    '
curl -s -o /dev/null -w '%{http_code}\n' -X POST "$GATEWAY/api/payments/webhooks/mock-gateway" \
  -H 'Content-Type: application/json' -d "$forged"
printf 'webhook signed with a guessed secret: '
curl -s -o /dev/null -w '%{http_code}\n' -X POST "$GATEWAY/api/payments/webhooks/mock-gateway" \
  -H 'Content-Type: application/json' -H "X-Webhook-Signature: t=$(date +%s),v1=$(printf '%s.%s' "$(date +%s)" "$forged" \
  | openssl dgst -sha256 -hmac guessed-secret | awk '{print $NF}')" -d "$forged"
curl -sf -X POST "$checkout_url" -H 'Content-Type: application/json' -d '{"outcome":"SUCCEEDED"}' \
  | jq -c '{transactionId, status: .payment.status}'

step "9. The saga confirms the booking and sells the seats (FR-BKG-09)"
wait_for_status alice "$alice_booking" CONFIRMED
booking alice "$alice_booking" | jq -c '{status, seats: [.seats[].seatCode]}'
curl -sf "$GATEWAY/api/events/$event_id/seats" | jq -c '{available, held, sold}'

step "10. ticket-service issues one signed QR ticket per seat (FR-TKT-01, FR-TKT-02)"
for _ in $(seq 1 30); do
  [ "$(curl -sf "$GATEWAY/api/tickets?bookingId=$alice_booking" -H "$(auth alice)" | jq length)" = 2 ] && break
  sleep 1
done
curl -sf "$GATEWAY/api/tickets?bookingId=$alice_booking" -H "$(auth alice)" \
  | jq -c '[.[] | {seatCode, qrToken: (.qrToken[0:20] + "...")}]'

step "11. notification-service emails the tickets (FR-NTF-01); open $MAILPIT to see them"
for _ in $(seq 1 30); do
  subject=$(curl -sf "$MAILPIT/api/v1/messages" \
    | jq -r --arg to "alice@ticketrush.dev" '[.messages[] | select(any(.To[]; .Address == $to))][0].Subject // empty')
  [ -n "$subject" ] && break
  sleep 1
done
[ -n "$subject" ] || fail "no ticket email for alice"
echo "email: $subject"

step "11b. At the door the organizer scans Alice's QR code: in once, refused the second time (FR-TKT-03)"
qr=$(curl -sf "$GATEWAY/api/tickets?bookingId=$alice_booking" -H "$(auth alice)" | jq -r '.[0].qrToken')
scan() { curl -s -X POST "$GATEWAY/api/tickets/check-in" -H "$(auth "$1")" -H 'Content-Type: application/json' \
  -d "{\"eventId\":\"$event_id\",\"qrToken\":\"$qr\"}"; }
printf 'alice scans her own ticket:  '; scan alice | jq -c '{status}'
printf 'organizer, first scan:       '; scan organizer | jq -c '{result, seatCode}'
printf 'organizer, second scan:      '; scan organizer | jq -c '{status, result, checkedInAt}'

step "12. Chi holds VIP-B-01 but the card is declined: booking cancelled, seat free again"
chi_booking=$(book chi "$(uuid)" VIP-B-01 | head -1 | jq -r .id)
wait_for_status chi "$chi_booking" AWAITING_PAYMENT
curl -sf -X POST "$(booking chi "$chi_booking" | jq -r .checkoutUrl)" -H 'Content-Type: application/json' \
  -d '{"outcome":"DECLINED"}' | jq -c '{status: .payment.status}'
wait_for_status chi "$chi_booking" CANCELLED
booking chi "$chi_booking" | jq -c '{status, cancelReason}'
sleep 1
curl -sf "$GATEWAY/api/events/$event_id/seats" | jq -c '[.seats[] | select(.code == "VIP-B-01") | {code, state}][0]'
for _ in $(seq 1 30); do
  [ "$(emails_to chi@ticketrush.dev 'Đơn đặt vé đã bị huỷ')" -ge 1 ] && break
  sleep 1
done
[ "$(emails_to chi@ticketrush.dev 'Đơn đặt vé đã bị huỷ')" -ge 1 ] || fail "no cancellation email for chi"
echo "chi was emailed the reason (FR-NTF-02)"

step "13. A sell-out event uses the waiting room; booking without an admission token is refused (FR-WR-03)"
hot_event=$(curl -sf -X POST "$GATEWAY/api/events" -H "$(auth organizer)" -H 'Content-Type: application/json' -d @- <<JSON | jq -r .id
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
curl -sf -X POST "$GATEWAY/api/events/$hot_event/publish" -H "$(auth organizer)" > /dev/null
for _ in $(seq 1 30); do
  curl -sf "$GATEWAY/api/events/$hot_event/seats" > /dev/null && break
  sleep 1
done
book_hot() { # user [admission-token]
  curl -s -w '\nHTTP %{http_code}\n' -X POST "$GATEWAY/api/bookings" \
    -H "$(auth "$1")" -H "Idempotency-Key: $(uuid)" -H "X-Admission-Token: ${2:-}" -H 'Content-Type: application/json' \
    -d "{\"eventId\":\"$hot_event\",\"seatCodes\":[\"GA-A-01\"]}"
}
book_hot dung | show '{status, detail, joinQueue}'

step "14. Dung joins the queue; the room has space, so he is admitted with a token (FR-WR-01)"
queue=$(curl -sf -X POST "$GATEWAY/api/queue/events/$hot_event/join" -H "$(auth dung)")
token=$(echo "$queue" | jq -r .admissionToken)
echo "$queue" | jq -c '{state, position, admittedUntil, admissionToken: (.admissionToken[0:24] + "...")}'

step "15. With the token his booking goes through"
book_hot dung "$token" | show '{id, status}'

step "16. Bob's account fires 20 booking requests at once: the gateway lets about 10 through per second (FR-GW-02)"
codes=$(for _ in $(seq 1 20); do
  curl -s -o /dev/null -w '%{http_code}\n' -X POST "$GATEWAY/api/bookings" -H "$(auth bob)" \
    -H "Idempotency-Key: $(uuid)" -H 'Content-Type: application/json' -d '{}' &
done; wait)
echo "$codes" | sort | uniq -c | awk '{printf "  %s x HTTP %s\n", $1, $2}'
echo "$codes" | grep -q 429 || fail "no request was rate limited"

printf '\n\033[32mAll steps passed.\033[0m\n'
