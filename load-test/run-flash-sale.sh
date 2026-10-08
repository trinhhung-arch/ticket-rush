#!/usr/bin/env bash
# Runs the flash sale and audits the result. With --flush-redis-at N, all Redis data (every seat
# hold) is wiped N seconds into the sale, the "Redis loses its data" scenario of the requirements.
#   ./load-test/run-flash-sale.sh                      # RATE=1000 DURATION=1m by default
#   ./load-test/run-flash-sale.sh --flush-redis-at 20
set -euo pipefail
cd "$(dirname "$0")/.."

flush_at=""
if [ "${1:-}" = "--flush-redis-at" ]; then
  flush_at=${2:?seconds}
fi
mkdir -p load-test/results
stamp=$(date +%Y%m%d-%H%M%S)
log=load-test/results/flash-sale-$stamp.log

echo "Warming up the JVMs for 20 s (not measured)..."
k6 run --quiet -e GATEWAY="${GATEWAY:-http://localhost:8080}" -e RATE=200 -e DURATION=20s \
  --no-thresholds load-test/flash-sale.js > /dev/null 2>&1 || true

if [ -n "$flush_at" ]; then
  # Count from the moment setup has created the event, i.e. when the sale starts.
  ( until grep -q 'EVENT_ID=' "$log" 2>/dev/null; do sleep 0.5; done
    sleep "$flush_at"
    echo ">>> $(date +%T) FLUSHALL on Redis: every seat hold is gone" | tee -a "$log"
    docker compose exec -T redis redis-cli FLUSHALL ) &
fi

k6 run -e GATEWAY="${GATEWAY:-http://localhost:8080}" -e RATE="${RATE:-1000}" -e DURATION="${DURATION:-1m}" \
  --summary-export "load-test/results/flash-sale-$stamp.json" load-test/flash-sale.js 2>&1 | tee "$log" || true
wait

event_id=$(grep -oE 'EVENT_ID=[0-9a-f-]{36}' "$log" | head -1 | cut -d= -f2)
echo "Waiting for the saga to settle..."
sleep 20
./scripts/check-oversell.sh "$event_id" | tee -a "$log"
