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

## ROS와 화면 연결

COMMAND_DURABLE_ENABLED를 API, Worker와 ROS에 함께 전달한다. 기본값 false 유지.
ROS는 /var/lib/robot-delivery의 영속 볼륨을 사용한다. 정적 모드도 활성화할 경우 Bridge와
로봇 프로세스가 같은 로컬 journal 경로와 Fleet을 사용해야 한다. 서버 간 SQLite 파일 공유는 금지한다.
한 Fleet의 관리 런타임은 여전히 하나다. 실행 서버 장애 인계는 5단계에서 별도 설계한다.

미확인 명령 기본 10,000개, 저장 예산 1GiB, 입력 payload 16KiB 상한을 적용한다.
미확인 명령마다 결과 기록용 64KiB를 예약해 접수 한도에 도달해도 결과를 저장할 수 있게 한다.
파일과 WAL 및 SHM을 합산하며, 실제 디스크 쓰기 실패 시 새 동작을 막고 로봇을 정지시킨다.
기록은 수동 정비 전까지 보존한다. 디스크가 영구적으로 가득 차면 기록을 삭제해 우회하지 않는다.

로봇 실행 의도부터 먼저 커밋하고 동작을 적용한다. 그 사이 종료되면 재실행하지 않고
OUTCOME_UNKNOWN으로 보고한다. 동작과 디스크 기록의 원자성을 가정하지 않는다.
삭제와 runtime 교체 후에도 과거 명령과 Ack를 회수하며 새 runtime에 전달하지 않는다.
Worker DB 수신 확인 전까지 Ack 사본을 보존한다. 같은 호스트의 로봇과 Bridge는
SQLite 보관함을 함께 읽으므로 ROS Ack 토픽 수신 전 종료도 복구할 수 있다.

Dashboard는 sessionStorage에 요청 키만 전송 전에 보관한다. 명령 payload는 저장하지 않고
자동 재전송도 하지 않는다. 접수 응답 유실 후 다른 API에서 키로 조회한다.
추적은 100개로 제한하며 조회는 4개씩 순환한다. 브라우저 저장 실패 시 새 전송을 막는다.

## 실제 통합 검증

```bash
COMMAND_DURABLE_ENABLED=true scripts/scalability/lab.sh up dual
RUN_COMMAND_DURABILITY=1 python3 -m unittest discover -s scripts/scalability -p test_command_durability.py -v
```

기존 확장성 격리 프로젝트를 사용한다. Worker, PostgreSQL 중단과 ROS 강제 종료를 포함하며
볼륨은 삭제하지 않는다. ROS 재시작은 기존 Kafka group 재할당 시간으로 최대 90초를 허용한다.

검증 기록: 격리 API 2대, 실제 ROS와 Worker 및 PostgreSQL에서 접수 응답 유실과 중복 요청,
ROS 강제 종료 후 이동 재개 방지, Worker 및 DB 중단 후 Ack 복구 3개 시나리오가
100.115초에 통과했다. 테스트는 각 시나리오의 로봇 준비 상태를 확인한 후 시작한다.
로봇 단위 19개, Bridge 44개 중 3개 별도 통합 생략, 화면 단위 31개 및 typecheck/build 통과.

병합 전 리뷰 보완: 로봇은 SQLite 잠금 대기 후 현재 시각으로 만료를 검사하고,
실행 의도 커밋 후 동작 적용 직전에 다시 검사한다. 대기 중 만료된 정지가 실행되지 않는지와
중복 명령이 동작 없이 같은 결과만 반환하는지 회귀 테스트로 검증한다.
handleCommand에 정상 역직렬화된 요청이 도달한 뒤 발생하는 검증 예외는
같은 /topic/cmd-result에 status=rejected, requestId와 robotId, errorCode 및 message를 반환한다.
cmdId는 null이다. 메시지 역직렬화 자체가 실패하면 컨트롤러에 도달하지 않으므로
요청 식별자가 포함된 rejected 응답을 보장하지 않는다.

STORAGE_UNAVAILABLE에서 접수 여부 조회는 영속 경로가 활성화되고 처음부터
idempotencyKey를 보낸 요청에만 가능하다. 키 없는 요청은 cmdId를 받지 못하면 조회할 수 없으며
자동으로 다시 보내지 않는다. 복구가 필요한 클라이언트는 첫 전송부터 idempotencyKey를 포함해야 한다.

레거시 경로의 동기 전달 예외만 DELIVERY_UNCERTAIN으로 표시한다. 비동기 Kafka 발행 실패는
accepted 응답 후 발생할 수 있으며 서버 로그에만 남고 클라이언트에 후속 실패 응답을 보내지 않는다.
레거시 명령에는 outbox 키 조회를 통한 복구를 보장하지 않는다.
