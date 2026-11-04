#!/usr/bin/env bash

set -euo pipefail

# Existing topics are validated; replica reassignment is an explicit maintenance operation.
TOPICS=(
  "telemetry.pose"
  "telemetry.battery"
  "telemetry.status"
  "cmd.robot"
  "ack.robot"
  "dlq.telemetry"
  "dlq.command"
)

BROKER="${KAFKA_BROKER_URL:-localhost:9092}"
REPLICATION="${KAFKA_REPLICATION_FACTOR:-1}"
MIN_ISR="${KAFKA_MIN_INSYNC_REPLICAS:-1}"
if ! [[ "$REPLICATION" =~ ^[1-9][0-9]*$ && "$MIN_ISR" =~ ^[1-9][0-9]*$ ]] || (( MIN_ISR > REPLICATION )); then
  echo 'Invalid replication factor or minimum ISR' >&2
  exit 2
fi

if command -v kafka-topics >/dev/null 2>&1; then
  kafka_topics_cmd=(kafka-topics)
elif command -v docker >/dev/null 2>&1; then
  kafka_topics_cmd=(docker exec robot_kafka kafka-topics)
else
  echo "kafka-topics 명령을 찾을 수 없습니다." >&2
  exit 1
fi

echo "Kafka 준비 대기 중: $BROKER"
for attempt in {1..30}; do
  if "${kafka_topics_cmd[@]}" --bootstrap-server "$BROKER" --list >/dev/null 2>&1; then
    break
  fi
  if [ "$attempt" -eq 30 ]; then
    echo "Kafka가 준비되지 않았습니다: $BROKER" >&2
    exit 1
  fi
  sleep 2
done

for TOPIC in "${TOPICS[@]}"
do
  echo "Creating topic: $TOPIC"
  "${kafka_topics_cmd[@]}" --create --if-not-exists \
    --bootstrap-server "$BROKER" \
    --partitions 3 \
    --replication-factor "$REPLICATION" \
    --config "min.insync.replicas=$MIN_ISR" \
    --config unclean.leader.election.enable=false \
    --topic "$TOPIC"
  description="$("${kafka_topics_cmd[@]}" --bootstrap-server "$BROKER" --describe --topic "$TOPIC")"
  if ! grep -Eq "ReplicationFactor:[[:space:]]*$REPLICATION([[:space:]]|$)" <<< "$description"; then
    echo "Replica count mismatch for $TOPIC; perform explicit reassignment without recreating the topic" >&2
    exit 1
  fi
  if command -v kafka-configs >/dev/null 2>&1; then
    config_cmd=(kafka-configs)
  else
    config_cmd=(docker exec robot_kafka kafka-configs)
  fi
  config="$("${config_cmd[@]}" --bootstrap-server "$BROKER" --describe --all --entity-type topics --entity-name "$TOPIC")"
  if ! grep -Eq "min.insync.replicas=$MIN_ISR([[:space:],]|$)" <<< "$config" || ! grep -Eq 'unclean.leader.election.enable=false([[:space:],]|$)' <<< "$config"; then
    echo "Durability configuration mismatch for $TOPIC; apply approved topic configuration changes" >&2
    exit 1
  fi
done

echo "All topics created successfully."
"${kafka_topics_cmd[@]}" --list --bootstrap-server "$BROKER"
