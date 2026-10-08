#!/usr/bin/env bash
# Topics and per-service ACLs (INF-03, INF-04). Every client signs in as itself over SASL/PLAIN and
# may only write the topics it owns and read the topics it consumes: nobody but notification-service
# reads ticket.events (QR codes, emails), nobody but payment-service writes payment.events.
# Runs as the super user once the broker is up, from docker compose (kafka-init) and from the Helm
# chart (a post-install hook). Running it again changes nothing.
set -euo pipefail

bootstrap="${KAFKA_BOOTSTRAP:-kafka:9092}"
partitions="${KAFKA_PARTITIONS:-12}"
replicas="${KAFKA_REPLICAS:-1}"
bin=/opt/kafka/bin

admin="$(mktemp)"
cat > "$admin" <<EOF
security.protocol=SASL_PLAINTEXT
sasl.mechanism=PLAIN
sasl.jaas.config=org.apache.kafka.common.security.plain.PlainLoginModule required username="admin" password="${KAFKA_ADMIN_PASSWORD:?}";
EOF

until "$bin/kafka-broker-api-versions.sh" --bootstrap-server "$bootstrap" --command-config "$admin" > /dev/null 2>&1; do
  echo "Waiting for Kafka at $bootstrap"
  sleep 2
done

# The topics of com.ticketrush.contracts.Topics and their dead-letter topics. Created here because no
# service may create topics; each one's KafkaAdmin only checks that they exist.
for topic in event.events booking.events payment.commands payment.events ticket.events; do
  for name in "$topic" "$topic-dlt"; do
    "$bin/kafka-topics.sh" --bootstrap-server "$bootstrap" --command-config "$admin" --create --if-not-exists \
      --topic "$name" --partitions "$partitions" --replication-factor "$replicas" > /dev/null
  done
done

acl() {
  "$bin/kafka-acls.sh" --bootstrap-server "$bootstrap" --command-config "$admin" --add "$@" > /dev/null
}

topic_flags() {
  for name in "$@"; do printf -- '--topic %s ' "$name"; done
}

# writes <user> <topic>...: the topics a service publishes (through its outbox).
writes() {
  local user="$1"; shift
  # shellcheck disable=SC2046
  acl --allow-principal "User:$user" --operation Write $(topic_flags "$@")
}

# reads <user> <topic>...: the topics a service consumes, the dead-letter topics its failed records go
# to, and its consumer groups (all named after the service).
reads() {
  local user="$1"; shift
  local dead_letters=()
  for name in "$@"; do dead_letters+=("$name-dlt"); done
  # shellcheck disable=SC2046
  acl --allow-principal "User:$user" --operation Read $(topic_flags "$@")
  # shellcheck disable=SC2046
  acl --allow-principal "User:$user" --operation Write $(topic_flags "${dead_letters[@]}")
  acl --allow-principal "User:$user" --operation Read --group "$user" --resource-pattern-type prefixed
}

# Topic metadata only (no messages), which KafkaAdmin and the producers need.
acl --allow-principal User:event-service --allow-principal User:booking-service \
    --allow-principal User:payment-service --allow-principal User:ticket-service \
    --allow-principal User:notification-service --allow-principal User:kafka-ui \
    --operation Describe --topic '*'

writes event-service        event.events
writes booking-service      booking.events payment.commands
reads  booking-service      event.events payment.events
writes payment-service      payment.events
reads  payment-service      payment.commands
writes ticket-service       ticket.events
reads  ticket-service       booking.events event.events
reads  notification-service ticket.events booking.events payment.events

# Kafka UI (docker compose only) browses everything but changes nothing; it sits behind its own login.
acl --allow-principal User:kafka-ui --operation Read --operation DescribeConfigs --topic '*'
acl --allow-principal User:kafka-ui --operation Describe --group '*'
acl --allow-principal User:kafka-ui --operation Describe --operation DescribeConfigs --cluster

rm -f "$admin"
echo "Kafka topics and ACLs are in place"
