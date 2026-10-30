#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
action="${1:-}"
mode="${2:-dual}"
case "$mode" in single|dual) ;; *) echo 'mode: single 또는 dual' >&2; exit 2 ;; esac
case "$action" in up|stop|status|config|build) ;; *) echo '사용법: lab.sh up|stop|status|config|build [single|dual]' >&2; exit 2 ;; esac
if [ "$action" != config ] && [ "$action" != status ] && [ "${ROKIN_SCALE_LOCK_HELD:-}" != 1 ]; then
  exec python3 "$root/scripts/scalability/lab_lock.py" "$root/scripts/scalability/lab.sh" "$@"
fi
export SIMULATOR_INTERNAL_TOKEN="$(python3 -c 'import secrets; print(secrets.token_hex(32))')"
export POSTGRES_USER=postgres POSTGRES_PASSWORD=scale_lab_only POSTGRES_DB=telemetry
export FLEET_ID=default SIMULATOR_MAX_ROBOTS=20
export KAFKA_GROUP_ID=telemetry-worker KAFKA_TOPICS=telemetry.pose,telemetry.battery,telemetry.status,ack.robot
export BATCH_SIZE=100 BATCH_FLUSH_MS=500 DLQ_TOPIC=dlq.telemetry METRICS_ADDR=:2112
unset COMPOSE_PROFILES
export SCALE_NGINX_CONFIG="$root/infra/scalability/nginx-$mode.conf"
export SCALE_PROMETHEUS_CONFIG="$root/infra/scalability/prometheus-$mode.yml"
compose=(docker compose --env-file /dev/null --project-name rokin-scale-lab -f "$root/infra/docker-compose.yml" -f "$root/infra/scalability/compose.yml")
if [ "$mode" = dual ]; then compose+=(-f "$root/infra/scalability/compose-dual.yml" --profile dual); fi
case "$action" in
  config) "${compose[@]}" config --quiet ;;
  build) "${compose[@]}" build api worker fake_robot dashboard ;;
  up)
    if [ "$mode" = single ]; then
      docker compose --env-file /dev/null --project-name rokin-scale-lab -f "$root/infra/docker-compose.yml" -f "$root/infra/scalability/compose.yml" --profile dual stop api-b
    fi
    "${compose[@]}" up -d --build --wait --wait-timeout 300
    "${compose[@]}" restart load-balancer
    "${compose[@]}" exec -T load-balancer nginx -t
    python3 - <<'PY'
import time
import urllib.request
deadline = time.monotonic() + 30
while True:
    try:
        with urllib.request.urlopen('http://localhost:18080/actuator/health', timeout=3) as response:
            break
    except OSError:
        if time.monotonic() >= deadline:
            raise
        time.sleep(.5)
PY
    "${compose[@]}" exec -T postgres psql -U postgres -d telemetry -Atc 'SELECT count(*) FROM pg_stat_statements' >/dev/null
    echo "준비 완료: $mode, Dashboard http://localhost:13000, API http://localhost:18080"
    ;;
  stop) "${compose[@]}" --profile dual stop ;;
  status) "${compose[@]}" --profile dual ps ;;
esac
