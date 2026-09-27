# Docker Compose 통합 실행 가이드

`infra/docker-compose.yml`은 PostgreSQL, Kafka, Spring API, Go Worker, ROS2 가상 로봇,
Next.js Dashboard, Prometheus, Grafana를 하나의 스택으로 실행합니다.

## 1. 환경 설정

루트 디렉터리에서 `.env.example`을 복사합니다. 생략하면 Compose의
기본값이 적용됩니다.

```bash
cp .env.example .env
```

호스트에서 기본 포트를 이미 사용 중이면 `POSTGRES_PORT`, `KAFKA_PORT`,
`ZOOKEEPER_PORT`, `API_PORT`, `DASHBOARD_PORT`, `PROMETHEUS_PORT`, `GRAFANA_PORT`를 변경합니다. `API_PORT`를 바꾸면
`NEXT_PUBLIC_API_URL`도 같은 포트로 맞춰야 합니다.

## 2. 전체 스택 실행

다음 명령 하나로 이미지를 빌드하고 전체 스택을 백그라운드에서 실행합니다.

```bash
docker compose -f infra/docker-compose.yml up -d --build
```

보조 스크립트를 사용해도 같은 결과를 얻습니다.

```bash
./scripts/dev.sh up
```

Compose는 PostgreSQL과 Kafka의 헬스체크를 통과한 뒤 Kafka 토픽을 멱등적으로
생성하고, Spring API의 Flyway 마이그레이션 완료 후 Worker와 로봇을 시작합니다.

## 3. 상태와 로그 확인

```bash
./scripts/dev.sh ps
./scripts/dev.sh logs
```

접속 주소의 기본값은 다음과 같습니다.

- Dashboard: `http://localhost:3000`
- Spring API: `http://localhost:8080`
- Swagger UI: `http://localhost:8080/swagger-ui/index.html`
- Prometheus: `http://localhost:9090`
- Grafana: `http://localhost:3001`
- Worker metrics: `http://localhost:2112/metrics`
- Kafka: `localhost:9092`
- PostgreSQL: `localhost:15432`

ROS2 토픽은 로봇 컨테이너 안에서 확인합니다.

```bash
docker exec -it robot_fake_nodes bash
source /opt/ros/humble/setup.bash
ros2 topic list
ros2 topic echo /fleet/robot_1/pose
```

## 4. 관측 도구 확인

전체 인프라를 시작하면 다음 도구가 함께 실행됩니다.

| 도구 | 주소 | 역할 |
| --- | --- | --- |
| Prometheus | `http://localhost:9090` | 애플리케이션과 exporter 지표 저장 |
| Grafana | `http://localhost:3001` | 자동 등록된 통합 대시보드 제공 |
| PostgreSQL exporter | `http://localhost:9187/metrics` | PostgreSQL 지표 노출 |
| Kafka exporter | `http://localhost:9308/metrics` | Kafka topic과 consumer lag 노출 |

Grafana 기본 계정은 `admin` / `admin`이며 `.env`의 `GRAFANA_ADMIN_USER`, `GRAFANA_ADMIN_PASSWORD`로 변경할 수 있습니다.

Prometheus의 `Status > Targets`에서 다음 job이 `UP`인지 확인합니다.

- `api-spring`
- `worker-go`
- `postgres-exporter`
- `kafka-exporter`
- `prometheus`

## 5. 서비스 정지

다음 명령은 컨테이너와 네트워크를 정리하지만 PostgreSQL 볼륨은 보존합니다.

```bash
./scripts/dev.sh down
```
