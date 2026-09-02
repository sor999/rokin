#!/usr/bin/env bash

set -euo pipefail

# 생성할 토픽 목록 (partition 3, replication-factor 1)
TOPICS=(
  "telemetry.pose"
  "telemetry.battery"
  "telemetry.status"
  "cmd.robot"
  "ack.robot"
  "dlq.telemetry"
)

BROKER="${KAFKA_BROKER_URL:-localhost:9092}"

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
    --replication-factor 1 \
    --topic "$TOPIC"
done

echo "All topics created successfully."
"${kafka_topics_cmd[@]}" --list --bootstrap-server "$BROKER"
