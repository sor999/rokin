#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
case "${1:-}" in
  up) docker compose --project-name rokin-durable-lab -f "$root/infra/durability/compose.yml" up -d --wait --wait-timeout 240 ;;
  stop) docker compose --project-name rokin-durable-lab -f "$root/infra/durability/compose.yml" stop ;;
  config) docker compose --project-name rokin-durable-lab -f "$root/infra/durability/compose.yml" config --quiet ;;
  test) RUN_KAFKA_DURABILITY=1 PYTHONPATH="$root/ros/src/fake_robot" python3 -m unittest discover -s "$root/scripts/durability" -p 'test_*.py' -v ;;
  *) echo 'Usage: kafka-lab.sh up|stop|config|test' >&2; exit 2 ;;
esac
