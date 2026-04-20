#!/usr/bin/env bash

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="$ROOT_DIR/infra/docker-compose.yml"
WORKER_IMAGE="${WORKER_IMAGE:-robot-worker}"
WORKER_NAME="${WORKER_NAME:-robot_worker}"
WORKER_GROUP_ID="${WORKER_GROUP_ID:-telemetry-worker-test}"

echo "[1/6] Stopping worker and fake robot containers"
docker rm -f "$WORKER_NAME" >/dev/null 2>&1 || true
docker stop robot_fake_nodes >/dev/null 2>&1 || true

echo "[2/6] Resetting Kafka and Postgres containers and volumes"
docker compose -f "$COMPOSE_FILE" down -v

echo "[3/6] Starting Postgres, Zookeeper, and Kafka"
docker compose -f "$COMPOSE_FILE" up -d postgres zookeeper kafka

echo "[4/6] Waiting for Kafka to become ready"
sleep 15

echo "[5/6] Recreating Kafka topics"
"$ROOT_DIR/infra/kafka/topic-init.sh"

echo "[6/6] Building and starting worker"
docker build -t "$WORKER_IMAGE" "$ROOT_DIR/services/worker-go" >/dev/null
docker run -d \
  --name "$WORKER_NAME" \
  --network infra_robot_net \
  -e POSTGRES_HOST=postgres \
  -e POSTGRES_PORT=5432 \
  -e POSTGRES_USER=postgres \
  -e POSTGRES_PASSWORD=postgres \
  -e POSTGRES_DB=telemetry \
  -e KAFKA_BROKER_URL=kafka:29092 \
  -e KAFKA_GROUP_ID="$WORKER_GROUP_ID" \
  -e KAFKA_TOPICS=telemetry.pose,telemetry.battery,telemetry.status,ack.robot \
  -e DLQ_TOPIC=dlq.telemetry \
  -e BATCH_SIZE=10 \
  -e BATCH_FLUSH_MS=1000 \
  "$WORKER_IMAGE" >/dev/null

cat <<EOF
Reset complete.

Current state:
- Postgres/Kafka restarted from clean volumes
- fake_robot stopped
- worker started with group id: $WORKER_GROUP_ID

Next steps:
  ./scripts/test-worker-go.sh
  docker logs -f $WORKER_NAME
EOF
