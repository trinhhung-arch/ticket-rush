#!/bin/bash
# One database and one owner role per service (NFR-MAINT-01): a service can only reach its own data.
# Runs once, when the Postgres volume is created.
set -euo pipefail

create_service_db() {
  local db="$1" role="$2" password="$3"
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres \
       -v db="$db" -v role="$role" -v password="$password" <<'SQL'
CREATE ROLE :"role" LOGIN PASSWORD :'password';
CREATE DATABASE :"db" OWNER :"role";
REVOKE CONNECT ON DATABASE :"db" FROM PUBLIC;
SQL
}

create_service_db event_db event_svc "$EVENT_DB_PASSWORD"
create_service_db booking_db booking_svc "$BOOKING_DB_PASSWORD"
create_service_db payment_db payment_svc "$PAYMENT_DB_PASSWORD"
create_service_db ticket_db ticket_svc "$TICKET_DB_PASSWORD"
create_service_db notify_db notify_svc "$NOTIFY_DB_PASSWORD"
# Keycloak's own database in the Helm chart (production mode, INF-05); docker compose runs Keycloak in
# dev mode on its embedded database and does not set the password.
if [ -n "${KEYCLOAK_DB_PASSWORD:-}" ]; then
  create_service_db keycloak_db keycloak_svc "$KEYCLOAK_DB_PASSWORD"
fi
