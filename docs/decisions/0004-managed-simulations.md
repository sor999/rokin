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
