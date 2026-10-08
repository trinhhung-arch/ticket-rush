#!/usr/bin/env bash
# Checks the Kafka sign-in and ACLs of the running docker compose stack (INF-03, INF-04): the attacks
# from the security test plan must fail, and the account that should read ticket.events can.
# Message contents are never printed. Run after ./scripts/smoke-test.sh, so the topics hold messages.
#   ./scripts/check-kafka-acls.sh
set -uo pipefail
cd "$(dirname "$0")/.."

# shellcheck disable=SC1091
set -a; . ./.env; set +a

failures=0
pass() { printf '  \033[32mPASS\033[0m %s\n' "$1"; }
fail() { printf '  \033[31mFAIL\033[0m %s\n' "$1"; failures=$((failures + 1)); }

# The client settings for <user> signed in with <password>; "-" = no SASL at all. Short timeouts, so a
# refusal shows up in seconds. Written inside the broker container, used by the Kafka CLI tools there.
client_props='
  props=$(mktemp)
  printf "%s\n" "request.timeout.ms=5000" "default.api.timeout.ms=8000" "max.block.ms=8000" "delivery.timeout.ms=10000" > "$props"
  if [ "$P" = "-" ]; then
    echo "security.protocol=PLAINTEXT" >> "$props"
  else
    printf "%s\n" "security.protocol=SASL_PLAINTEXT" "sasl.mechanism=PLAIN" \
      "sasl.jaas.config=org.apache.kafka.common.security.plain.PlainLoginModule required username=\"$U\" password=\"$P\";" >> "$props"
  fi
'

topics() { # user password: list the topics
  docker compose exec -T -e U="$1" -e P="$2" kafka bash -c "$client_props"'
    timeout 30 /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --command-config "$props" --list 2>&1'
}

read_one() { # user password topic: read one message in assign mode (no consumer group needed)
  docker compose exec -T -e U="$1" -e P="$2" kafka bash -c "$client_props"'
    for p in $(seq 0 11); do
      out=$(timeout 30 /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:9092 --consumer.config "$props" \
        --topic "'"$3"'" --partition "$p" --offset earliest --max-messages 1 --timeout-ms 5000 2>&1 > /dev/null)
      case "$out" in *"Processed a total of 1 messages"*) echo "READ_OK"; exit 0;; *uthoriz*) echo "DENIED"; exit 0;; esac
    done
    echo "NO_MESSAGES"
  '
}
write_one() { # user password topic: try to publish one forged message
  docker compose exec -T -e U="$1" -e P="$2" kafka bash -c "$client_props"'
    echo "{\"forged\":true}" | timeout 30 /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server kafka:9092 \
      --producer.config "$props" --topic "'"$3"'" 2>&1
  '
}

echo "Kafka sign-in and ACLs"

out=$(topics anonymous -); if [[ "$out" != *event.events* ]]; then pass "no credentials: the broker lists nothing"; else fail "no credentials: topics were listed"; fi

out=$(topics booking-service wrong-password)
if [[ "$out" == *"Authentication failed"* ]]; then pass "wrong password: sign-in refused"; else fail "wrong password: topics were listed"; fi

case "$(read_one booking-service "$KAFKA_BOOKING_SERVICE_PASSWORD" ticket.events)" in
  DENIED) pass "INF-04: booking-service cannot read ticket.events (QR codes, emails)";;
  *) fail "INF-04: booking-service read ticket.events";;
esac
case "$(read_one kafka-ui "$KAFKA_UI_PASSWORD" ticket.events)" in
  READ_OK) pass "Kafka UI's read-only account can browse (behind the UI login)";;
  *) fail "Kafka UI's account cannot browse ticket.events";;
esac
case "$(read_one notification-service "$KAFKA_NOTIFICATION_SERVICE_PASSWORD" ticket.events)" in
  READ_OK) pass "notification-service reads ticket.events, as it must to send tickets";;
  *) fail "notification-service cannot read ticket.events";;
esac

out=$(write_one booking-service "$KAFKA_BOOKING_SERVICE_PASSWORD" payment.events)
if [[ "$out" == *uthoriz* ]]; then pass "INF-03: booking-service cannot forge payment.events"; else fail "INF-03: booking-service wrote to payment.events"; fi
out=$(write_one kafka-ui "$KAFKA_UI_PASSWORD" booking.events)
if [[ "$out" == *uthoriz* ]]; then pass "Kafka UI's account cannot write"; else fail "Kafka UI's account wrote to booking.events"; fi

if [ "$failures" -eq 0 ]; then echo "All Kafka checks passed."; else echo "$failures Kafka check(s) failed."; exit 1; fi
