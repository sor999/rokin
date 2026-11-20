#!/usr/bin/env bash
set -euo pipefail
root="$(cd "$(dirname "$0")/../.." && pwd)"
action="${1:-}"
case "$action" in up|stop|config|status) ;; *) echo '사용법: lab.sh up|stop|config|status' >&2; exit 2 ;; esac
if [ "$action" != config ] && [ "$action" != status ] && [ "${ROKIN_FAILOVER_LOCK_HELD:-}" != 1 ]; then
  exec python3 "$root/scripts/failover/lock.py" "$root/scripts/failover/lab.sh" "$@"
fi
export SIMULATOR_INTERNAL_TOKEN="$(python3 -c 'import secrets; print(secrets.token_hex(32))')"
export POSTGRES_USER=postgres POSTGRES_PASSWORD=runtime_lab_only POSTGRES_DB=telemetry
export REALTIME_REDIS_PASSWORD=runtime_lab_relay_only
export FLEET_ID=default SIMULATOR_MAX_ROBOTS=20 SIMULATOR_LEASE_ENABLED=true
export KAFKA_GROUP_ID=telemetry-worker KAFKA_TOPICS=telemetry.pose,telemetry.battery,telemetry.status,ack.robot
export BATCH_SIZE=100 BATCH_FLUSH_MS=500 DLQ_TOPIC=dlq.telemetry METRICS_ADDR=:2112
export SCALE_NGINX_CONFIG="$root/infra/scalability/nginx-dual.conf"
export SCALE_PROMETHEUS_CONFIG="$root/infra/scalability/prometheus-dual.yml"
unset COMPOSE_PROFILES
compose=(docker compose --env-file /dev/null --project-name rokin-runtime-lab -f "$root/infra/docker-compose.yml" -f "$root/infra/scalability/compose.yml" -f "$root/infra/scalability/compose-dual.yml" -f "$root/infra/failover/compose.yml" --profile dual)
case "$action" in
 config) "${compose[@]}" config --quiet ;;
 status) "${compose[@]}" ps ;;
 stop) "${compose[@]}" stop ;;
 up)
  "${compose[@]}" up -d --build --wait --wait-timeout 300 api api-b worker load-balancer fake_robot fake_robot-b
  echo '실행 인계 검증 환경 준비 완료: API 18491/18492, LB 18490'
 ;;
esac
