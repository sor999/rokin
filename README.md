# Robot Telemetry Platform

로봇 텔레메트리 수집, 저장, 실시간 스트리밍, 제어 명령 전송을 한 저장소에서 다루는 모노레포입니다.  
ROS2 기반 가상 로봇이 텔레메트리를 발행하면 Kafka를 통해 Go Worker와 Spring API가 이를 처리하고, Next.js 대시보드가 실시간 상태 조회와 명령 전송 UI를 제공합니다.

## 한눈에 보기

- ROS2 가상 로봇과 Kafka 브릿지를 통해 로봇 텔레메트리와 명령을 비동기 처리합니다.
- Go Worker가 Kafka 메시지를 배치 적재하여 PostgreSQL에 저장합니다.
- Spring Boot API가 REST, SSE, WebSocket(STOMP)로 대시보드와 로봇 네트워크를 연결합니다.
- Next.js 대시보드가 Fleet Overview, 개별 로봇 상세, 배터리/위치 이력, 명령 패널을 제공합니다.
- 개발용 스크립트로 인프라와 애플리케이션을 한 번에 띄울 수 있습니다.

## 아키텍처

```text
ROS2 fake_robot
  -> ros_kafka_bridge
  -> Kafka (telemetry.*, ack.robot, cmd.robot)
  -> Go Worker -> PostgreSQL
  -> Spring Boot API
  -> Next.js Dashboard

Dashboard command
  -> Spring WebSocket/STOMP
  -> Kafka cmd.robot
  -> ros_kafka_bridge
  -> ROS2 robot
  -> ack.robot
  -> Spring API / Dashboard
```

### 데이터 흐름

1. `fake_robot`가 `/fleet/{robot_id}` 토픽으로 pose, battery, status, ack를 발행합니다.
2. `ros_kafka_bridge`가 ROS2 메시지를 Kafka 토픽(`telemetry.pose`, `telemetry.battery`, `telemetry.status`, `ack.robot`)으로 전달합니다.
3. `worker-go`가 Kafka를 consume해서 PostgreSQL에 배치 적재합니다.
4. `api-spring`이 DB 조회용 REST API를 제공하고, Kafka 이벤트를 받아 SSE/WebSocket으로 클라이언트에 브로드캐스트합니다.
5. `dashboard-next`가 REST로 초기 상태를 불러오고, SSE와 STOMP로 실시간 상태 및 Ack를 반영합니다.

## 저장소 구조

```text
.
├── infra/                  # Docker Compose, Kafka 토픽 초기화 스크립트
├── ros/                    # ROS2 fake_robot, ros_kafka_bridge
├── services/
│   ├── api-spring/         # Spring Boot API, SSE, WebSocket, Swagger
│   ├── worker-go/          # Kafka Consumer + PostgreSQL batch writer
│   └── dashboard-next/     # Next.js 대시보드
├── scripts/                # 개발/테스트/빌드 보조 스크립트
├── contracts/              # 메시지/도메인 계약 관련 자산
└── docs/                   # PR 초안 등 문서
```

## 서비스 구성

| 영역 | 경로 | 역할 | 주요 기술 |
| --- | --- | --- | --- |
| ROS2 | `ros/` | 가상 로봇 시뮬레이션, Kafka 브릿지 | ROS2 Humble, Python |
| API | `services/api-spring/` | REST, SSE, WebSocket, Kafka integration | Spring Boot 3.5, Java 25 |
| Worker | `services/worker-go/` | Kafka consume, PostgreSQL batch insert, DLQ 처리 | Go 1.22, pgx, confluent-kafka-go |
| Dashboard | `services/dashboard-next/` | 실시간 관제 UI, 로봇 상세, 명령 패널 | Next.js 16, React 19, Zustand |
| Infra | `infra/` | PostgreSQL, Kafka, Zookeeper, fake robot 컨테이너 | Docker Compose |

## 빠른 시작

### 사전 준비

- Docker / Docker Compose
- Java 25
- Go 1.22+
- Node.js 20+ 및 `pnpm`

### 1. 환경 변수 준비

```bash
cp .env.example .env
```

기본값만으로도 로컬 실행이 가능합니다.

### 2. 대시보드 의존성 설치

```bash
cd services/dashboard-next
pnpm install
cd ../..
```

### 3. 전체 개발 환경 실행

```bash
./scripts/dev.sh up
```

이 스크립트는 아래 순서로 서비스를 띄웁니다.

- Docker Compose 인프라 실행
- Kafka 토픽 초기화
- Go Worker 실행
- Spring Boot API 실행
- Next.js Dashboard 실행

### 4. 접속 주소

- Dashboard: `http://localhost:3000`
- Spring API: `http://localhost:8080`
- Swagger UI: `http://localhost:8080/swagger-ui/index.html`
- Kafka: `localhost:9092`
- PostgreSQL: `localhost:5432`

### 5. 종료

```bash
./scripts/dev.sh down
```

로그만 보고 싶다면:

```bash
./scripts/dev.sh logs
```

## 수동 실행

### 인프라 실행

```bash
docker compose -f infra/docker-compose.yml up -d
./infra/kafka/topic-init.sh
```

### Go Worker 실행

```bash
cd services/worker-go
go run ./cmd/...
```

기본 환경 변수:

- `POSTGRES_HOST=localhost`
- `POSTGRES_PORT=5432`
- `POSTGRES_USER=postgres`
- `POSTGRES_PASSWORD=postgres`
- `POSTGRES_DB=telemetry`
- `KAFKA_BROKER_URL=localhost:9092`
- `KAFKA_GROUP_ID=telemetry-worker`
- `KAFKA_TOPICS=telemetry.pose,telemetry.battery,telemetry.status,ack.robot`
- `DLQ_TOPIC=dlq.telemetry`

### Spring API 실행

```bash
cd services/api-spring
./gradlew bootRun
```

### Next.js Dashboard 실행

```bash
cd services/dashboard-next
pnpm dev
```

필요하면 API 주소를 명시적으로 지정할 수 있습니다.

```bash
NEXT_PUBLIC_API_URL=http://localhost:8080 pnpm dev
```

### ROS2 fake_robot 실행

```bash
docker compose -f infra/docker-compose.yml up fake_robot --build
```

ROS2 CLI 테스트는 컨테이너 내부에서 수행합니다.

```bash
docker exec -it robot_fake_nodes bash
source /opt/ros/humble/setup.bash
export ROS_LOCALHOST_ONLY=1
```

## 핵심 토픽

| 토픽 | 설명 |
| --- | --- |
| `telemetry.pose` | 로봇 위치 텔레메트리 |
| `telemetry.battery` | 로봇 배터리 텔레메트리 |
| `telemetry.status` | 로봇 상태 텔레메트리 |
| `cmd.robot` | 대시보드/백엔드에서 로봇으로 전달하는 명령 |
| `ack.robot` | 로봇이 반환하는 명령 Ack |
| `dlq.telemetry` | Worker 처리 실패 메시지의 DLQ |

## 개발 보조 스크립트

| 스크립트 | 설명 |
| --- | --- |
| `scripts/dev.sh` | 전체 개발 환경 실행/중지/로그 조회 |
| `scripts/build-worker-go.sh` | Worker Docker 이미지 빌드 |
| `scripts/test-worker-go.sh` | Worker Go 테스트 실행 |
| `scripts/reset-test-env.sh` | Kafka/Postgres 및 Worker 테스트 환경 초기화 |

## 서비스별 문서

- [infra/README.md](infra/README.md)
- [ros/README.md](ros/README.md)
- [services/api-spring/README.md](services/api-spring/README.md)
- [services/dashboard-next/README.md](services/dashboard-next/README.md)

## 현재 상태 메모

- 루트 `README.md`는 프로젝트 입구 문서 역할에 집중합니다.
- 세부 구현과 도메인 규칙은 각 서비스 디렉터리와 `AGENTS.md`, `.agents/skills/` 문서를 참고하면 됩니다.
