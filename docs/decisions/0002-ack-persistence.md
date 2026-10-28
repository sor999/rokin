# ADR 0002: Flyway 단일 소유권과 Ack 상태 전이

- 상태: 승인
- 날짜: 2026-10-28
- 관련 이슈: https://github.com/sor999/rokin/issues/17
- 대체: ADR 0001의 Worker 호환 DDL 예외

## 맥락

Flyway에는 Ack 중복 제약이 없고 Worker는 자체 DDL로 이를 보완했다. Flyway만 적용한 DB의
Worker INSERT는 충돌 대상 인덱스를 찾지 못한다. timestamp를 포함한 중복 키는 시간이 달라진
동일 종료 Ack를 다시 적재하므로 종료 상태 전이에 충분하지 않다.

## 결정

Worker의 자체 DDL을 제거한다. API/Flyway가 먼저 마이그레이션을 적용하고 Worker는 시작 시
필요한 테이블과 컬럼을 읽기 전용으로 확인한다. Compose의 기존 API health 의존성을 유지한다.
단독 Worker 실행도 API 마이그레이션을 먼저 적용해야 한다.

`robot_ack_state`의 `cmd_id` 기본 키로 명령별 상태 전이를 직렬화한다.
최초 `accepted`, `done`, `failed`는 허용하고, 이후에는 `accepted`에서 `done` 또는 `failed`로의
전이만 허용한다. 종료 상태 중 먼저 적재된 상태를 유지한다. 같은 명령의 robot_id가 다른
후속 Ack는 상태를 바꾸지 않는다. timestamp가 달라진 재전송도 중복으로 처리한다.
상태 변경과 이력 INSERT는 하나의 SQL 문장과 트랜잭션으로 수행한다.

V1/V2와 기존 Ack 이력을 수정하거나 삭제하지 않는다. V3는 기존 명령의 첫 종료 Ack를 우선하고,
종료 Ack가 없으면 첫 accepted를 상태 테이블에 반영한다. timestamp 동률은 id로 결정한다.
기존 중복 이력은 보존되지만 배포 후 같은 전이가 새 이력으로 추가되지 않는다.
배포 시 이전 Worker를 중지한 뒤 API 마이그레이션과 새 Worker 시작 순서를 지킨다.
이전 Worker와 새 Worker의 동시 쓰기는 지원하지 않는다.

## 결과와 한계

이번 변경은 영속 Ack 이력의 멱등성을 보장한다. API의 Kafka→WebSocket 브로드캐스트와
로봇의 명령 중복 실행 방지는 별도 경계이며 이 변경으로 무손실 전달이나 실행 중복 방지를 보장하지 않는다.

## 검증 시나리오

| 시나리오 | 준비 상태 | 자동 검증 | 수동 검증 |
| --- | --- | --- | --- |
| 빈 DB | 격리 PostgreSQL 스키마 | Flyway V1~V3 후 Worker Ack 적재 | API 먼저 시작 후 Worker 연결 |
| 기존 DB | V1/V2, 중복·충돌 Ack 이력 | V3 backfill과 이력 보존 | 배포 순서 확인 |
| 중복과 역순 | 같은 cmd_id, 다른 timestamp | accepted 중복, 종료 후 accepted/다른 종료 무시 | 이력 조회 |
| 동시 종료 | 별도 커넥션의 done/failed | 종료 이력 1개와 최종 상태 일치 | 해당 없음 |
| 미적용 스키마 | V1/V2만 적용 | Worker 시작 검증 실패와 DDL 미실행 | Flyway 적용 후 재시작 |

API 단위 테스트는 H2를 유지한다. PostgreSQL 마이그레이션 검증과 Worker 적재 통합 테스트는
각각 `MIGRATION_TEST_URL`과 `TEST_DATABASE_URL`을 지정한 격리 DB에서 실행한다.
