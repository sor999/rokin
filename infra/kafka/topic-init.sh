#!/bin/bash

# 카프카 브로커가 띄워질 때까지 대기 (간단한 sleep 또는 넷캣/헬스체크 활용)
echo "Waiting for Kafka to be ready..."
sleep 15

# 생성할 토픽 목록 (partition 3, replication-factor 1)
TOPICS=(
  "telemetry.pose"
  "telemetry.battery"
  "telemetry.status"
  "cmd.robot"
  "ack.robot"
)

BROKER="localhost:9092"

for TOPIC in "${TOPICS[@]}"
do
  echo "Creating topic: $TOPIC"
  docker exec robot_kafka kafka-topics --create --if-not-exists \
    --bootstrap-server $BROKER \
    --partitions 3 \
    --replication-factor 1 \
    --topic $TOPIC
done

echo "All topics created successfully."
docker exec robot_kafka kafka-topics --list --bootstrap-server $BROKER
