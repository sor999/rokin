#!/usr/bin/env bash
# Docker Compose 통합 환경 관리
# 사용법: ./scripts/dev.sh [up|down|logs|ps]

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE_FILE="$ROOT_DIR/infra/docker-compose.yml"

start() {
  docker compose -f "$COMPOSE_FILE" up -d --build
  docker compose -f "$COMPOSE_FILE" ps
}

stop() {
  docker compose -f "$COMPOSE_FILE" down
}

show_logs() {
  docker compose -f "$COMPOSE_FILE" logs -f
}

show_status() {
  docker compose -f "$COMPOSE_FILE" ps
}

case "${1:-up}" in
  up) start ;;
  down) stop ;;
  logs) show_logs ;;
  ps) show_status ;;
  *)
    echo "Usage: $0 [up|down|logs|ps]"
    exit 1
    ;;
esac
