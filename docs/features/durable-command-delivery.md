# 명령과 Ack 영속 전달

## 구현 순서

1. API와 DB 계약: 명령 outbox, 멱등 접수, 명령 조회, Worker Ack 수신 확인
2. ROS와 화면 연결: SQLite 수신함, 실행 기록과 결과 보관, 응답 유실 복구
3. Kafka 복제와 장애 검증: 별도 3대 구성, 복제와 재전송 확인

전체 경로 연결 전 COMMAND_DURABLE_ENABLED=false를 유지한다.

## 시나리오

| 시나리오 | 준비 상태 | 자동 검증 | 수동 검증 |
| --- | --- | --- | --- |
| 접수와 재시작 | 전용 PostgreSQL | 응답 전에 DB 저장, 재시작 API에서 같은 키 조회 | 접수와 실행 상태 구분 |
| 중복과 경쟁 | API 2대 | 같은 키 8개 경쟁, 내용 충돌 거부, Fleet 격리 | 동일 명령 재실행 없음 |
| 장애와 재전송 | 만료 임대와 DB 실패 | 임대 토큰 fencing, 미확인 만료, 수신 후 재전송 중단 | 복구 결과 조회 |
| Ack 재전달 | Worker와 새 event_id | 수신 확인과 상태 전이 원자성, 종료 상태 역행 차단 | 최종 DB 확인 후 로컬 정리 |

## 인터페이스

- STOMP 요청의 idempotencyKey는 선택 UUID. requestId는 기존 연결 의미 유지.
- GET /api/commands/{cmdId}, GET /api/commands?idempotencyKey={uuid}: Fleet 내 명령 조회, 없으면 404.
- deliveryStatus: PENDING, RECEIVED, EXPIRED, OUTCOME_UNKNOWN, COMPLETED. 실제 결과는 ack에 별도 포함.
- POST /internal/commands/received: 최대 100개의 cmdId, robotId, outcomeUnknown 보고.
- POST /internal/commands/ack-receipts: 최대 100개의 event_id 조회. eventIds에 DB 저장 확인된 ID 반환.
- 내부 API는 X-Simulator-Token 사용. 서버 전용이며 브라우저에 노출하지 않는다.

## 검증

PRESENCE_TEST_URL과 선택 PRESENCE_TEST_USER 및 PRESENCE_TEST_PASSWORD를 설정해 DurableCommandPostgresTest를 실행한다.
Worker는 TEST_DATABASE_URL에서 TestDurableAckReceiptAtomicity를 실행한다.
기본 H2 테스트만으로 PostgreSQL 임대와 경쟁 검증을 대체하지 않는다.

STOMP 검증 실패는 같은 /topic/cmd-result에 status=rejected, requestId와 robotId, errorCode 및 message를 반환한다. cmdId는 null이다. STORAGE_UNAVAILABLE에서 접수 여부 조회는 영속 경로가 활성화되고 처음부터 idempotencyKey를 보낸 요청에만 가능하다. 키 없는 요청은 cmdId를 받지 못하면 조회할 수 없으며 자동으로 다시 보내지 않는다. 복구가 필요한 클라이언트는 첫 전송부터 idempotencyKey를 포함해야 한다. 레거시 전달 실패는 DELIVERY_UNCERTAIN으로 표시하며 조회 가능 여부를 가정하지 않는다.
