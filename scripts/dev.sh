#!/usr/bin/env bash
# 전체 개발 환경 한 번에 기동
# 사용법: ./scripts/dev.sh [up|down|logs]

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
INFRA_DIR="$ROOT_DIR/infra"
API_DIR="$ROOT_DIR/services/api-spring"
WORKER_DIR="$ROOT_DIR/services/worker-go"
DASH_DIR="$ROOT_DIR/services/dashboard-next"

RED='\033[0;31m'
GREEN='\033[0;32m'
CYAN='\033[0;36m'
NC='\033[0m'

log() { echo -e "${CYAN}[dev]${NC} $1"; }
ok()  { echo -e "${GREEN}[dev]${NC} $1"; }
err() { echo -e "${RED}[dev]${NC} $1"; }

PID_FILE="$ROOT_DIR/.dev-pids"

# 실행 중인 프로세스 정리
cleanup() {
  log "Shutting down services..."
  if [ -f "$PID_FILE" ]; then
    while IFS= read -r pid; do
      kill "$pid" 2>/dev/null || true
    done < "$PID_FILE"
    rm -f "$PID_FILE"
  fi
  docker compose -f "$INFRA_DIR/docker-compose.yml" down 2>/dev/null || true
  ok "All services stopped."
}

# 서비스 기동
start() {
  # 기존 PID 파일 정리
  rm -f "$PID_FILE"
  touch "$PID_FILE"

  trap cleanup EXIT INT TERM

  # 1) 인프라 (Postgres + Kafka + fake_robot)
  log "Starting infrastructure (Docker Compose)..."
  docker compose -f "$INFRA_DIR/docker-compose.yml" up -d
  ok "Infrastructure containers started."

  # 2) Kafka 토픽 초기화 (이미 존재하면 무시)
  log "Initializing Kafka topics..."
  bash "$INFRA_DIR/kafka/topic-init.sh" &
  TOPIC_PID=$!
  echo "$TOPIC_PID" >> "$PID_FILE"

  # 3) Go Worker
  log "Starting Worker (Go)..."
  (cd "$WORKER_DIR" && go run ./cmd/...) &
  WORKER_PID=$!
  echo "$WORKER_PID" >> "$PID_FILE"
  ok "Worker started (PID: $WORKER_PID)"

  # 4) Spring Boot API
  log "Starting API server (Spring Boot)..."
  (cd "$API_DIR" && ./gradlew bootRun --console=plain -q) &
  API_PID=$!
  echo "$API_PID" >> "$PID_FILE"
  ok "API server starting (PID: $API_PID)"

  # 5) Next.js Dashboard
  log "Starting Dashboard (Next.js)..."
  (cd "$DASH_DIR" && pnpm dev) &
  DASH_PID=$!
  echo "$DASH_PID" >> "$PID_FILE"
  ok "Dashboard starting (PID: $DASH_PID)"

  echo ""
  ok "========================================"
  ok "  All services starting!"
  ok "  Dashboard  → http://localhost:3000"
  ok "  API        → http://localhost:8080"
  ok "  Kafka      → localhost:9092"
  ok "  Postgres   → localhost:5432"
  ok "========================================"
  echo ""
  log "Press Ctrl+C to stop all services."

  # 모든 백그라운드 프로세스 대기
  wait
}

# 중지
stop() {
  cleanup
}

# 로그 보기
show_logs() {
  docker compose -f "$INFRA_DIR/docker-compose.yml" logs -f
}

case "${1:-up}" in
  up)    start ;;
  down)  stop ;;
  logs)  show_logs ;;
  *)
    echo "Usage: $0 [up|down|logs]"
    exit 1
    ;;
esac
