# Robot Telemetry Platform Architecture

## 시스템 경계

```text
fake_robot -> ROS2 topics -> ros_kafka_bridge -> Kafka
                                              |-> worker -> PostgreSQL
                                              |-> api -> Redis pub/sub -> all APIs -> SSE/WebSocket -> dashboard

dashboard -> STOMP command -> api -> cmd.robot -> ros_kafka_bridge
               <- Ack/WebSocket <- all APIs <- Redis pub/sub <- api <- ack.robot <- fake_robot
```

각 런타임은 독립 배포 단위입니다. 서비스 사이에 소스 코드 의존성을 만들지 않고 Kafka,
HTTP, SSE, WebSocket/STOMP, PostgreSQL 스키마 같은 명시적 계약으로만 연결합니다.

## 소유권

| 경계 | 소유자 | 기준 위치 |
| --- | --- | --- |
| ROS 토픽과 로봇 동작 | ROS2 패키지 | `ros/src/` |
| Kafka 텔레메트리 배치 적재 | Go Worker | `services/worker/` |
| DB 스키마 | Spring API/Flyway | `services/api/src/main/resources/db/migration/` |
| REST, SSE, WebSocket 계약 | Spring API | `services/api/src/main/java/com/robot/fleet/domain/` |
| 브라우저 상태와 표시 | Next.js Dashboard | `services/dashboard/src/` |
| 로컬 인프라와 토픽 생성 | Infra | `infra/` |

Worker는 시작 시 필요한 스키마를 읽기 전용으로 확인하며 DDL을 실행하지 않습니다.
API/Flyway 마이그레이션을 먼저 적용해야 합니다. Compose는 API가 준비된 뒤 Worker를
시작합니다. Ack 상태 전이와 기존 이력 보존은 [ADR 0002](docs/decisions/0002-ack-persistence.md)를 따릅니다.

## 데이터 흐름의 불변식

- Telemetry는 단방향 관측 데이터이며 동일한 `event_id`를 두 번 적용하지 않습니다.
- Command는 `cmd_id`와 `fleet_id`로 라우팅하며 로봇 프로세스의 TTL 범위에서 중복 실행을 막습니다.
  Fleet 소유권과 group 규칙은 [ADR 0003](docs/decisions/0003-bridge-command-ownership.md)을 따릅니다.
- Ack는 Command의 수명주기 상태입니다. `accepted`, 진행/완료, 실패 상태의 의미를 생산자와 소비자가 공유합니다.
- 실시간 스트림은 유실 또는 재연결될 수 있습니다. Dashboard는 REST 초기 상태 이후 SSE/WS 이벤트를 합성합니다.
- Kafka 전달은 at-least-once일 수 있으므로 소비 성공 전에 영속성/후속 처리 결과를 확인합니다.

상세 필드와 용어는 [Telemetry 도메인 문서](docs/domain/telemetry.md)를 따릅니다.

## 변경 영향 지도

| 변경 | 함께 확인할 영역 |
| --- | --- |
| Kafka 토픽/Envelope | ROS bridge, Worker handler, Spring listener/DTO, `infra/kafka/topic-init.sh` |
| DB 컬럼/인덱스 | Flyway, JPA Entity/Repository, Worker SQL |
| REST 응답 | Spring DTO/Controller 테스트, Dashboard `src/types`와 API client |
| Command/Ack 상태 | Dashboard command UI/store, Spring service/listener, ROS command handler |
| 실시간 이벤트 타입 | Spring stream service/DTO, Dashboard hooks/store |

## 런타임 의존성

- 로컬 통합 환경: Docker Compose, PostgreSQL 15, Kafka/Zookeeper
- API: Java 25와 Gradle Wrapper
- Worker: Go 1.22와 `librdkafka`
- Dashboard: Node.js 20+와 `pnpm`
- ROS2: Docker 이미지 내 ROS2 Humble

빠른 실행은 루트 [README](README.md), 검증 단계는
[검증 컨벤션](docs/conventions/verification.md)을 사용합니다.

## 동적 시뮬레이터 관리

Dashboard의 등록·삭제는 API의 DB 실행 의도를 변경한다. ROS 관리 런타임이 이를 조회해
로봇과 Bridge 연결을 수렴시킨다. 정적 모드와의 소유권 구분 및 재시작 계약은
[ADR 0004](docs/decisions/0004-managed-simulations.md), 실행 설정과 검증 시나리오는
[가상 로봇 관리](docs/features/simulator-management.md)를 따른다.

## API 간 실시간 전달

Compose의 API는 Kafka 소비 후 Redis Fleet 채널을 통해 모든 연결 서버에 관측·Ack를 전달한다.
STOMP SimpleBroker는 로컬 연결에만 전송하는 말단이며 API 간 전달은 Redis가 담당한다.
단절 시 연결을 닫고, 재접속한 화면은 REST로 현재 상태와 알려진 명령의 Ack를 복구한다.
경로·버퍼·복구 의미는 [ADR 0005](docs/decisions/0005-realtime-fanout.md)와
[실행·검증 문서](docs/features/realtime-fanout.md)를 따른다.

## API의 공유 온라인 판정

Kafka를 소비한 API가 PostgreSQL presence에 관측 시각을 저장하고 모든 API가 DB의
현재 runtime, 준비 상태, heartbeat 및 관측 만료를 함께 확인한다. Redis와 SSE 전송은
온라인 유효기간을 갱신하지 않는다. 저장소 장애 시 명령을 거부하며 복구와 시계 계약은
[ADR 0006](docs/decisions/0006-shared-presence.md)을 따른다.

## 최신 상태 조회와 Fleet 등록 동시성

Worker의 이력 INSERT와 같은 DB 트랜잭션에서 최신 상태 투영을 갱신한다. 목록은 투영과
공유 온라인 상태를 한 번에 읽으며, 등록과 runtime 변경은 Fleet별로 조정한다.
소유권 확인은 DB 기준을 유지하고 TTL 캐시를 추가하지 않는다. 갱신 순서와 배포 전환은
[ADR 0007](docs/decisions/0007-fleet-database-scalability.md)을 따른다.

## 명령과 Ack 영속 전달

선택적으로 API의 PostgreSQL outbox와 ROS의 SQLite WAL/FULL 저널을 연결한다.
명령은 실행 전에 기록하고 Ack는 Worker DB 확인 전까지 재전송 사본을 보존한다.
전송과 실행 결과, 만료 및 결과 불확실 상태를 구분하며 기본 설정은 비활성화다.
[ADR 0008](docs/decisions/0008-durable-command-delivery.md)과
[전환 및 검증](docs/features/durable-command-delivery.md)에 범위와 복구 계약을 기록한다.

## Worker의 제한된 소비와 파티션 소유권

Worker는 같은 group에서 텔레메트리와 Ack를 서로 다른 Consumer 및 DB 풀로 처리한다.
파티션별 고정 배치와 예약 예산으로 DB 장애 시 소비를 중지하고, 할당 세대로 이전
작업의 커밋을 차단한다. [ADR 0009](docs/decisions/0009-worker-backpressure.md)와
[부하 및 장애 검증](docs/features/worker-backpressure.md)에 상한과 재처리 계약을 기록한다.

## 단일 활성 ROS 런타임의 장애 인계

API의 PostgreSQL lease와 epoch로 Fleet당 활성 관리 런타임을 하나로 제한한다. 대기
런타임은 명령 Consumer와 로봇을 생성하지 않는다. 만료된 실행은 로컬 기한으로 명령과
이동 갱신을 중단하며, 새 실행은 저장된 시작 위치와 초기 배터리로 복구한다. runtime별
ROS 토픽과 epoch별 Kafka group으로 이전 실행을 격리한다. 서버별 영속 볼륨은 공유하지
않으며 과거 명령의 결과는 해당 디스크가 복구된 뒤 회수한다.
[ADR 0010](docs/decisions/0010-runtime-lease-failover.md)과
[실행 및 검증](docs/features/runtime-failover.md)에 조건과 전환 절차를 기록한다.
