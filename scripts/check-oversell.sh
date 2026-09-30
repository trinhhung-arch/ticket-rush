#!/usr/bin/env bash
# Audits one event after a load test (NFR-CORR-01, NFR-CORR-04): no seat sold twice, every sold
# seat belongs to a confirmed booking, and every paid booking that lost its seat was refunded.
#   ./scripts/check-oversell.sh <eventId>
set -euo pipefail
cd "$(dirname "$0")/.."
event_id=${1:?usage: check-oversell.sh <eventId>}
set -a; . ./.env; set +a

booking_sql() { docker compose exec -T -e PGPASSWORD="$BOOKING_DB_PASSWORD" postgres \
  psql -h localhost -U booking_svc -d booking_db -tA -F ' ' -c "$1"; }
payment_sql() { docker compose exec -T -e PGPASSWORD="$PAYMENT_DB_PASSWORD" postgres \
  psql -h localhost -U payment_svc -d payment_db -tA -F ' ' -c "$1"; }

echo "Event $event_id"
echo "Bookings by status:"
booking_sql "select status, coalesce(cancel_reason, '-'), count(*) from booking where event_id = '$event_id' group by 1, 2 order by 1, 2" \
  | awk '{printf "  %-17s %-15s %s\n", $1, $2, $3}'

duplicates=$(booking_sql "
  select count(*) from (
    select s.seat_code from booking_seat s join booking b on b.id = s.booking_id
    where b.event_id = '$event_id' and b.status = 'CONFIRMED'
    group by s.seat_code having count(*) > 1) d")
sold=$(booking_sql "select count(*) from seat_inventory where event_id = '$event_id' and status = 'SOLD'")
confirmed_seats=$(booking_sql "select count(*) from booking_seat s join booking b on b.id = s.booking_id
  where b.event_id = '$event_id' and b.status = 'CONFIRMED'")
conflicts=$(booking_sql "select coalesce(string_agg(quote_literal(id::text), ','), '') from booking
  where event_id = '$event_id' and cancel_reason = 'SEAT_CONFLICT'")
refunded=0
if [ -n "$conflicts" ]; then
  refunded=$(payment_sql "select count(*) from payment where status = 'REFUNDED' and booking_id::text in ($conflicts)")
fi
conflict_count=$(booking_sql "select count(*) from booking where event_id = '$event_id' and cancel_reason = 'SEAT_CONFLICT'")

echo "Seats sold:                        $sold"
echo "Seats in confirmed bookings:       $confirmed_seats"
echo "Seats sold to more than one:       $duplicates"
echo "Paid but seat taken (refunded):    $refunded / $conflict_count"

if [ "$duplicates" = 0 ] && [ "$sold" = "$confirmed_seats" ] && [ "$refunded" = "$conflict_count" ]; then
  echo "PASS: no oversell"
else
  echo "FAIL"
  exit 1
fi
