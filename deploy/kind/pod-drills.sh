#!/usr/bin/env bash
# NFR-AVAIL-04 and NFR-AVAIL-06 on the kind cluster: under steady traffic, one instance of a service
# is stopped normally, one is killed outright, and one Deployment is restarted pod by pod.
# Reports how many requests failed and over how long a window.
#   ./deploy/kind/up.sh && ./deploy/kind/pod-drills.sh
set -euo pipefail
cd "$(dirname "$0")/../.."
NS=ticketrush
GATEWAY=http://localhost:28080
KEYCLOAK=http://localhost:28180
mkdir -p load-test/results
log=load-test/results/pod-drills-$(date +%Y%m%d-%H%M%S).log

password=$(kubectl -n $NS get secret ticketrush-secrets -o jsonpath='{.data.DEMO_USER_PASSWORD}' | base64 -d)
token=$(curl -sf "$KEYCLOAK/realms/ticketrush/protocol/openid-connect/token" -d grant_type=password \
  -d client_id=ticketrush-cli -d username=organizer@ticketrush.dev --data-urlencode "password=$password" | jq -r .access_token)
starts=$(date -u -v+30d +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -d '+30 days' +%Y-%m-%dT%H:%M:%SZ)
opens=$(date -u -v-1H +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -d '-1 hour' +%Y-%m-%dT%H:%M:%SZ)
event_id=$(curl -sf -X POST "$GATEWAY/api/events" -H "Authorization: Bearer $token" -H 'Content-Type: application/json' \
  -d "{\"name\":\"Pod drill\",\"venue\":\"Nhà hát Lớn\",\"city\":\"Hanoi\",\"startsAt\":\"$starts\",\"salesOpenAt\":\"$opens\",
       \"sections\":[{\"code\":\"GA\",\"name\":\"Standard\",\"rows\":10,\"seatsPerRow\":50,\"priceVnd\":500000}]}" | jq -r .id)
curl -sf -X POST "$GATEWAY/api/events/$event_id/publish" -H "Authorization: Bearer $token" > /dev/null
until curl -sf "$GATEWAY/api/events/$event_id/seats" > /dev/null; do sleep 1; done

now_ms() { python3 -c 'import time; print(int(time.time() * 1000))'; }
note() { echo ">>> $(now_ms) $*" | tee -a "$log"; }
pod_of() { kubectl -n $NS get pods -l app.kubernetes.io/name="$1" -o jsonpath='{.items[0].metadata.name}'; }

( sleep 20
  pod=$(pod_of booking-service); note "delete pod $pod (graceful: preStop, then 30 s to drain)"
  kubectl -n $NS delete pod "$pod" --wait=false > /dev/null
  sleep 25
  pod=$(pod_of event-service); note "kill pod $pod (grace period 0: a crash)"
  kubectl -n $NS delete pod "$pod" --grace-period=0 --force > /dev/null 2>&1
  sleep 25
  note "rollout restart deployment/booking-service"
  kubectl -n $NS rollout restart deployment/booking-service > /dev/null
  kubectl -n $NS rollout status deployment/booking-service --timeout=5m > /dev/null
  note "rollout finished" ) &

k6 run -e GATEWAY="$GATEWAY" -e EVENT_ID="$event_id" load-test/steady-reads.js 2>&1 | tee -a "$log" > /dev/null || true
wait

python3 - "$log" <<'PY'
import re, sys
lines = open(sys.argv[1]).read().splitlines()
marks = [(int(m.group(1)), m.group(2)) for m in (re.search(r'>>> (\d+) (.*)', l) for l in lines) if m]
fails = [(int(m.group(1)), m.group(2), m.group(3)) for m in (re.search(r'FAILED (\d+) (\S+) (\d+)', l) for l in lines) if m]
total = next((l for l in lines if 'http_reqs' in l), '').split()
print(f"requests: {total[1] if len(total) > 1 else '?'}, failed: {len(fails)}")
for i, (at, what) in enumerate(marks):
    until = marks[i + 1][0] if i + 1 < len(marks) else float('inf')
    window = [f for f in fails if at <= f[0] < until]
    span = (window[-1][0] - window[0][0]) / 1000 if window else 0
    codes = sorted({f[2] for f in window})
    print(f"  {what}: {len(window)} failed over {span:.1f} s {codes if codes else ''}")
PY
