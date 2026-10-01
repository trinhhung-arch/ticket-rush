#!/usr/bin/env bash
# Fails fast, with the fix, when the running stack does not trust the load-test token issuer.
#   ./load-test/preflight.sh http://localhost:8080
set -euo pipefail
cd "$(dirname "$0")/.."
gateway=${1:-http://localhost:8080}
set -a; . ./.env; set +a
: "${LOAD_TEST_JWT_SECRET:?missing in .env: run ./scripts/init-dev-env.sh}"

b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }
now=$(date +%s)
header=$(printf '{"alg":"HS256","typ":"JWT"}' | b64url)
payload=$(printf '{"iss":"ticketrush-load-test","aud":"ticketrush-api","sub":"preflight","email":"preflight@example.com","roles":["CUSTOMER"],"iat":%d,"exp":%d}' "$now" $((now + 60)) | b64url)
signature=$(printf '%s.%s' "$header" "$payload" | openssl dgst -sha256 -hmac "$LOAD_TEST_JWT_SECRET" -binary | b64url)

# Services that were just (re)started answer 503 for a while; give them up to two minutes.
for _ in $(seq 1 40); do
  status=$(curl -s -o /dev/null -w '%{http_code}' "$gateway/api/bookings" -H "Authorization: Bearer $header.$payload.$signature" || true)
  [ "$status" = 200 ] || [ "$status" = 401 ] && break
  sleep 3
done
if [ "$status" != 200 ]; then
  echo "The stack at $gateway answered $status to a load-test token. Start it with the load-test overlay:" >&2
  echo "  docker compose -f docker-compose.yml -f load-test/compose.yml up -d" >&2
  exit 1
fi
