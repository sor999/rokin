# Robot Telemetry Platform Architecture

## 시스템 경계

```text
fake_robot -> ROS2 topics -> ros_kafka_bridge -> Kafka
                                              |-> worker -> PostgreSQL
                                              |-> api -> SSE/WebSocket -> dashboard

dashboard -> STOMP command -> api -> cmd.robot -> ros_kafka_bridge
               <- Ack/WebSocket <- api <- ack.robot <- fake_robot
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
