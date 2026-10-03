# Load tests

k6 scripts for the scenarios in section 6 of the requirements. Results and how to read them are in
[docs/load-test-report.md](../docs/load-test-report.md).

## Tokens for 20,000 customers

Every API call needs a JWT (FR-IAM-01). Creating 20,000 Keycloak accounts and logging each one in
would measure Keycloak rather than TicketRush, so k6 signs its own tokens: `iss` is
`ticketrush-load-test`, HS256 with `LOAD_TEST_JWT_SECRET` from `.env`. The gateway and the services
still verify signature, issuer, audience and expiry exactly as they do for Keycloak tokens.

That issuer is trusted only when the stack is started with the overlay in this folder:

```bash
docker compose -f docker-compose.yml -f load-test/compose.yml up -d --build
```

The plain `docker-compose.yml` and the Helm chart never set it, so a normal stack accepts Keycloak
tokens only. `preflight.sh` checks the overlay is on before a run.

## Running

```bash
./load-test/run-flash-sale.sh                      # 1,000 holds/s for 1 min, then the audit
DURATION=5m ./load-test/run-flash-sale.sh --flush-redis-at 120
set -a; . ./.env; set +a
k6 run -e GATEWAY=http://localhost:$GATEWAY_PORT -e LOAD_TEST_JWT_SECRET="$LOAD_TEST_JWT_SECRET" load-test/waiting-room.js
k6 run -e GATEWAY=http://localhost:$GATEWAY_PORT -e LOAD_TEST_JWT_SECRET="$LOAD_TEST_JWT_SECRET" load-test/read-paths.js
```

Lower trace sampling first, or the collector becomes the bottleneck:
`TRACING_SAMPLING_PROBABILITY=0.01` in `.env`.
