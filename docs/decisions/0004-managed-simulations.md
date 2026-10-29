# ADR 0004: DB 의도에 수렴하는 단일 가상 로봇 런타임

- 상태: 승인
- 관련 이슈: https://github.com/sor999/rokin/issues/31

## 맥락과 결정

고정 launch의 3대 대신 Dashboard에서 로봇을 생성·삭제한다. API/Flyway가 등록 설정과
실행 의도를 소유하고 ROS 관리 노드가 2초 주기로 이를 조회한다. 네트워크 조회는 별도 스레드,
로봇 생성·해제와 ROS 리소스 변경은 SingleThreadedExecutor에서 수행한다. 조회 실패는 현재
로봇을 유지한다. 준비 완료는 Kafka 할당과 ROS 명령/관측 DDS 연결 이후 보고한다.

동시 등록 상한과 전역 ID 중복 판정은 DB의 단일 registry 행을 FOR UPDATE로 잠가 직렬화한다.
삭제 시 설정 버전을 증가시키고 tombstone을 유지한다. 상태 보고는 버전이 맞는 경우만 반영한다.
실제 종료 완료까지 실행 슬롯을 유지하며 telemetry 테이블과 Worker의 계약은 변경하지 않는다.

ADR 0003의 정적 Bridge는 기존 규칙을 유지한다. 별도의 managed 모드는 Fleet의 단일 런타임이
전체 등록 로봇을 담당하고 group을 `<prefix>.<fleet>.managed.v1`로 고정한다. 로봇 추가·삭제는
다른 Bridge로 소유권을 이전하는 작업이 아니므로 기존 명령 offset과 로봇 동작을 유지한다.
정적 Bridge와 managed 모드는 동일 Fleet에서 동시에 배치하지 않으며 active-active 실행은 지원하지 않는다.

API는 관리 로봇 명령에 `simulation_version`, `simulation_runtime_id`를 포함한다. 관리 런타임은
프로세스별 UUID와 현재 로봇 버전이 모두 맞는 명령만 기존 라우터로 전달한다. 삭제한 로봇과
재시작 이전 인스턴스의 명령은 DLQ로 전달해 새 로봇이 과거 이동을 재개하지 않도록 한다.
기존 cmd_id dedup·DLQ 확인 후 offset commit 규칙은 유지한다.

## 실패와 한계

내부 조회·보고는 공유 비공개 토큰으로 인증한다. token 미설정은 실패로 처리한다. 토큰 갱신은
API와 관리 런타임을 함께 재시작한다. DB 설정 버전은 오래된 보고를 거부하며 RUNNING heartbeat가
10초 동안 없거나 telemetry가 오프라인이면 API가 신규 명령을 거부한다.

삭제는 비동기로 수렴하므로 접수 전에 발행된 명령이 종료 직전까지 실행될 수 있다. API 접수는
물리적 정지 보장이 아니다. API·ROS 장애 중 로봇 동작은 기존 로직을 따르며 장애 후 안전 정지는
별도 과제다. 실행 복구는 시작 위치와 배터리 100%에서 시작하고 진행 중 명령을 재개하지 않는다.

## 재시작 경계 보완

V5의 `simulation_runtime_session`은 등록 순서의 epoch를 보존한다. 관리 런타임은 실행 전
인증된 `/internal/simulations/runtime`에 UUID를 등록한다. 같은 현재 UUID의 등록은 멱등이며
한 번 교체된 UUID는 다시 등록할 수 없다. 상태 보고는 현재 등록 UUID와 설정 버전이 모두
맞는 경우만 반영한다. API 재시작에도 이 판정을 보존한다. 동시 active-active 배치는 지원하지 않는다.

관리 Bridge는 pose/battery/status envelope에 선택 필드 `simulation_runtime_id`를 부여한다.
Worker는 이를 nullable 컬럼에 보존하고 API는 현재 runtime의 행만 최신 상태에 사용한다.
SSE의 `runtimeId`, REST 로봇 DTO의 `runtimeId`를 클라이언트의 등록 상태와 비교하여
이전 실행의 늦은 이벤트·REST 응답을 버린다. 상세 화면의 이력은 runtimeId 쿼리로 현재
실행에 한정하고, 쿼리를 생략한 기존 이력 API는 과거 실행 데이터도 보존해 제공한다.

전환 시 Flyway V6를 먼저 적용한 뒤 Worker와 관리 ROS 런타임을 함께 갱신한다.
정적 메시지에서 runtime 필드를 생략하는 기존 경로는 nullable 값으로 호환한다.

텔레메트리 인덱스는 V6에서 CONCURRENTLY로 생성하며 같은 이름의 .sql.conf에서
executeInTransaction=false를 지정한다. 신규 V5에는 런타임 메타데이터와 컬럼 추가만 둔다.
현재 세션과 일치하는 이벤트만 수신하고 온라인 판정에는 서버 수신 시각을 사용한다.
따라서 이전 인스턴스의 미래 시각 이벤트가 현재 로봇의 온라인 복구를 막지 않는다.

Flyway의 PostgreSQL advisory lock은 spring.flyway.postgresql.transactional-lock=false로
세션 잠금을 사용한다. 동시 인덱스 생성이 Flyway 자체 트랜잭션을 기다리는 상황을 방지한다.
설정 근거: https://documentation.red-gate.com/flyway/reference/configuration/flyway-namespace/flyway-postgresql-namespace/flyway-postgresql-transactional-lock-setting
