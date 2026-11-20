# Spring 데이터 접근 조사와 JPA 적용 범위

## 조사 범위

`services/api/src/main/java`의 `JpaRepository`, `EntityManager`, `JdbcTemplate`, `java.sql`,
네이티브 쿼리와 호출 서비스 및 테스트를 확인했다. Worker의 SQL은 별도 서비스의 적재 경로이며
이번 리팩터링 대상에 포함하지 않는다. 스키마 소유권과 적재 경계는 [아키텍처](../../ARCHITECTURE.md)를 따른다.

| 접근 방식 | 위치 | 실제 사용 |
| --- | --- | --- |
| Spring Data JPA | `TelemetryPoseRepository`, `TelemetryBatteryRepository` | `RobotQueryService`의 로봇·runtime별 시간 역순 이력과 `Pageable` 제한 |
| Spring Data JPA | `TelemetryStatusRepository` | 상태 엔티티의 최신 조회 메서드를 제공한다. 현재 운영 서비스에서 직접 호출하지 않는다. |
| Spring Data JPA | `RobotAckRepository` | `AckQueryService`의 robot ID + cmd ID별 최신 Ack 조회 |
| JPQL | `TelemetryPoseRepository.findDistinctRobotIds` | DISTINCT 조회를 정의하지만 현재 목록 경로는 이를 호출하지 않고 최신 투영을 읽는다. |
| Spring Data JPA 쿼리 메서드 | `SimulationRobotRepository` | 이번에 분리한 시뮬레이터 목록·단건·실행 중 개수·소유 Fleet 조회 |
| JdbcTemplate | `SimulationRepository` | Fleet 락, 등록·조건부 삭제·보고, DB 시각, runtime 조회와 등록, 이력 존재 확인 |
| JdbcTemplate | `PresenceRepository` | 관측의 원자적 저장, 현재 소유권과 준비 상태 및 만료 판정, 과거 관측 정리 |
| JdbcTemplate | `RobotSnapshotRepository` | 최신 pose/battery/status와 공유 온라인 상태를 한 쿼리로 합성하고 cursor로 제한 |
| JdbcTemplate | `CommandOutboxRepository` | 멱등 요청 조회·저장, sequence, 선점·재전송·수신 확인, 전달 상태 지표 |

운영 코드에 `DriverManager`나 직접 `Connection`/`PreparedStatement`로 쿼리를 실행하는 별도
경로는 없다. `SimulationRepository.lockRuntimeForReport`는 JDBC connection metadata로
PostgreSQL의 `FOR SHARE`와 H2의 테스트용 `FOR UPDATE`를 구분한다. JPA repository의
`@Query`는 JPQL이며 `nativeQuery = true`는 사용하지 않는다.
테스트의 JDBC는 fixture 구성, DB 결과 단언, 마이그레이션, 실제 연결과 락 제어에도 쓰이므로 유지한다.

## 변경한 조회

`SimulationRepository.findAll`(두 오버로드), `find`, `countActive`, `findOwner`는
`SimulationRobotRepository`의 파생 쿼리 메서드에 위임한다. `SimulationRobot`은 기존 `simulation_robot` 스키마를
매핑하는 `@Immutable` 읽기 엔티티다. 외부 응답은 기존 `SimulationDto`를 유지한다.
저장소는 Spring Data `Repository`를 상속해 조회 메서드만 노출하며 엔티티 쓰기 API는 추가하지 않는다.

- 목록과 단건은 쿼리 메서드의 클래스 기반 DTO 투영으로 DTO만 읽고 영속성 컨텍스트에 엔티티를 적재하지 않는다.
  JDBC로 report/delete한 뒤 다시 읽을 때 1차 캐시의 오래된 엔티티를 반환하지 않는다.
- 활성 개수·목록 필터는 기존과 같이 `runtime_state <> 'DELETED'`다. `DELETING`도 상한에
  포함하고, 소유권 조회는 다른 Fleet과 tombstone을 포함한다.
- 로봇 ID 정렬과 단건 Fleet 조건, nullable runtime/error/reportedAt을 보존한다.
- 조건은 고정된 키 조회와 삭제 상태 필터다. 전체 목록과 활성 목록은 각각의 쿼리 메서드로
  분리한다. 직접 작성한 EntityManager/JPQL은 제거했고 `countBy`는 개수, 소유권 조회는
  `Owner` record 투영에서 Fleet 문자열만 반환한다. 동적 조건 조합이 필요한 조회에만
  QueryDSL을 적용하며, 현재 변경 범위에는 필요하지 않아 의존성을 추가하지 않는다.

## JDBC를 유지한 이유

| 경로 | 유지 근거 |
| --- | --- |
| Fleet 락과 seed 등록 | `INSERT ... ON CONFLICT DO NOTHING`과 `FOR UPDATE`/`FOR SHARE`의 순서가 생성 상한과 runtime 전환을 직렬화한다. 단순 `exists` 후 `save`로 바꾸면 경합과 예외 발생 시점이 달라진다. |
| 시뮬레이터 쓰기 | 삭제의 조건부 버전 증가와 보고의 버전·현재 실행 조건을 DB에서 한 번에 적용한다. 일반 엔티티 read-modify-save는 잃어버린 갱신과 오래된 보고 수용 위험이 있다. 삭제만 JPQL bulk update로 옮기는 것은 가능하지만, 등록·보고와 쓰기 소유권을 한 JDBC 경로에 유지한다. |
| runtime과 DB 시각 | 최신 epoch 조회와 현재 runtime 조건이 실행 소유권을 판정한다. `clock_timestamp`를 PostgreSQL의 트랜잭션 시작 시각인 `CURRENT_TIMESTAMP`나 JVM 시각으로 대체하면 긴 트랜잭션의 heartbeat 만료 판정이 바뀐다. |
| 이력 존재 확인 | 네 이력 테이블의 `EXISTS`를 한 왕복으로 검사한다. 네 개의 JPA exists 호출은 왕복을 늘리며, 로봇 ID 재사용 차단을 분리할 이득이 작다. |
| Presence | MATERIALIZED 판정 시각 CTE, 조건부 UPSERT/RETURNING, 원본 timestamp의 단조성, 소유권·heartbeat를 같은 SQL 스냅샷에서 평가한다. 엔티티 저장으로 풀면 동시 관측 및 재전송 의미가 달라진다. prune도 같은 DB 시각 계약을 유지한다. |
| 최신 스냅샷 | UNION/CTE, 최신 투영의 여러 종류와 presence 조인, runtime fencing, cursor를 하나의 SQL로 수행한다. 엔티티별 조회로 바꾸면 N+1이나 여러 스냅샷 간 불일치 위험이 있다. JPA native query로 옮겨도 SQL 의존성은 줄지 않는다. |
| 명령 outbox | sequence의 UPDATE RETURNING, SKIP LOCKED CTE 선점, lease token, 원자적인 수신 확인과 DB 시각 기반 지표가 핵심이다. byId/byKey/save는 개별적으로 JPA로 옮길 수 있지만, native 갱신과 동일 행을 관리하게 되는 비용을 피하고 outbox 저장소의 일관된 접근 방식을 유지한다. |

JDBC를 유지한다는 판단은 JPQL로 표현이 불가능하다는 뜻이 아니다. 락·원자성·시각·왕복 수를
보존하려면 상당 부분 native SQL이 남고, JPA로 감싸는 것만으로는 유지보수나 성능상의 이득이 작다.

## 트랜잭션과 성능 검토

서비스의 기존 `@Transactional`과 readOnly/timeout 경계를 유지했다. Boot가 구성하는
`JpaTransactionManager`는 동일 DataSource의 JDBC 연결을 노출하므로 JdbcTemplate 쓰기와
JPA 조회가 같은 트랜잭션을 사용한다. PostgreSQL 통합 테스트도 혼합 조회를 사용하는 경로는
`DataSourceTransactionManager` 대신 실제 `JpaTransactionManager`를 사용하도록 변경했다.
독립 JDBC 전용 테스트의 트랜잭션 관리자는 바꾸지 않았다.

투영은 엔티티 그래프나 연관관계를 추가하지 않으며 목록은 한 SELECT, 개수는 DB COUNT,
소유권은 Fleet 문자열 하나를 읽는다. Hibernate statistics로 목록의 statement 1개와
entity load 0개를 검증한다. 지연 시간·처리량 향상이나 운영 실행 계획 개선을 주장하지 않는다.
스키마·인덱스·공개 계약·락 순서·상태 전이는 이번 JPA 변경에서 바꾸지 않으므로 새 ADR은
추가하지 않는다. 기존 [최신 투영과 Fleet 락 결정](../decisions/0007-fleet-database-scalability.md)을 유지한다.

## 검증

`SimulationRobotRepositoryTest`는 Fleet 격리·정렬·활성 필터·빈 결과·nullable 필드·시각 매핑,
동일 영속성 컨텍스트에서 JDBC 갱신 직후 재조회, 쿼리 수, JPA/JDBC 트랜잭션 공유와 롤백을 검증한다.
기존 `SimulationServiceTest`는 중복 등록·상한·삭제 멱등성·오래된 보고와 runtime 전환을 검증한다.
PostgreSQL의 Fleet/presence 테스트는 격리 DB의 임시 스키마에서 실행한다.
해당 테스트의 EntityManagerFactory는 `validate`로 새 엔티티와 실제 Flyway 스키마의 매핑도 검사한다.
H2 결과를 PostgreSQL 락이나 native SQL 검증으로 간주하지 않는다.

기존 `AckMigrationTest`는 V3을 기대하면서 target 없이 최신 마이그레이션까지 실행하고 있었다.
Ack 전이 스키마 검증은 `target("3")`으로 고정했다. 최신 전체 마이그레이션 검증은 Fleet 등
PostgreSQL 통합 테스트가 담당한다. 이로써 후속 비트랜잭션 인덱스 생성과 Flyway 트랜잭션 락이
경합하는 기존 테스트 구성도 피한다.

H2에서는 SQL 스크립트로 생성한 `simulation_robot`을 Hibernate가 다시 drop/create하지 않도록
테스트 전용 schema filter를 사용한다. 테이블의 제약과 의존 뷰를 보존하며 validate에서는 제외하지 않는다.
운영은 기존 `ddl-auto: validate`와 Flyway 소유권을 유지한다.
실행 명령과 환경 의존 검증의 구분은 [검증 컨벤션](verification.md)을 따른다.

이번 검증은 격리 PostgreSQL 15.19의 `PRESENCE_TEST_URL`, `MIGRATION_TEST_URL`,
`FENCE_TEST_URL`을 지정해 `./gradlew test build`를 실행했다. 병행 작업의 Gradle 실행과
충돌하지 않도록 `--project-cache-dir`과 init script로 캐시 및 build 디렉터리를 `/tmp`에 분리했다.
main 기반으로 분리한 브랜치에서 총 115개 중 114개 성공, 실패 0개, Worker 제한 역할
권한 테스트 1개는 `WORKER_ROLE_TEST_ENABLED` 미지정으로 건너뛰었다.
`git diff --check`와 문서 링크 확인도 통과했다.
Kafka/Redis/ROS를 연결한 전체 서비스 통합과 브라우저 검증, 운영 규모 부하 측정은 수행하지 않았다.
