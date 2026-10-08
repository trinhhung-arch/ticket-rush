#!/usr/bin/env bash
# Failure drills from section 6 of the requirements, under steady load, followed by the audit:
#   ./load-test/run-chaos.sh payment   # NFR-AVAIL-01: payment-service stopped for 2 minutes
#   ./load-test/run-chaos.sh kafka     # NFR-CORR-02: the Kafka broker stopped for 60 seconds
# Buyers hold seats at RATE per second (default 50) for 4 minutes and keep their checkout page open
# for up to 4 minutes, retrying through the outage. The target is taken down 30 s into the sale.
set -euo pipefail
cd "$(dirname "$0")/.."
target=${1:?usage: run-chaos.sh payment|kafka}
case "$target" in
  payment) service=payment-service; outage=${OUTAGE:-120} ;;
  kafka) service=kafka; outage=${OUTAGE:-60} ;;
  *) echo "usage: run-chaos.sh payment|kafka" >&2; exit 1 ;;
esac

set -a; . ./.env; set +a
GATEWAY=${GATEWAY:-http://localhost:${GATEWAY_PORT:-8080}}
./load-test/preflight.sh "$GATEWAY"
mkdir -p load-test/results
stamp=$(date +%Y%m%d-%H%M%S)
log=load-test/results/chaos-$target-$stamp.log

( until grep -q 'EVENT_ID=' "$log" 2>/dev/null; do sleep 0.5; done
  sleep 30
  echo ">>> $(date +%T) docker compose stop $service" | tee -a "$log"
  docker compose stop "$service" > /dev/null 2>&1
  sleep "$outage"
  echo ">>> $(date +%T) docker compose start $service" | tee -a "$log"
  docker compose start "$service" > /dev/null 2>&1
  date +%s > "$log.restarted"
  echo ">>> $(date +%T) $service is back" | tee -a "$log" ) &

k6 run -e GATEWAY="$GATEWAY" -e LOAD_TEST_JWT_SECRET="$LOAD_TEST_JWT_SECRET" -e RATE="${RATE:-50}" \
  -e DURATION="${DURATION:-4m}" -e PAY_PATIENCE=240 -e GRACEFUL_STOP=5m -e VUS=3000 --no-thresholds \
  --summary-export "load-test/results/chaos-$target-$stamp.json" load-test/flash-sale.js 2>&1 | tee -a "$log" || true
wait

event_id=$(grep -oE 'EVENT_ID=[0-9a-f-]{36}' "$log" | head -1 | cut -d= -f2)
restarted=$(cat "$log.restarted"); rm -f "$log.restarted"
echo "Load stopped at $(date +%T); auditing until consistent (NFR-CORR-04)..." | tee -a "$log"
for _ in $(seq 1 48); do
  if ./scripts/reconcile.py "$event_id" > /dev/null 2>&1; then
    echo ">>> consistent $(( $(date +%s) - restarted )) s after $service came back" | tee -a "$log"
    break
  fi
  sleep 10
done
./scripts/reconcile.py "$event_id" | tee -a "$log"
