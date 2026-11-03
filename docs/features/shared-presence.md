# 공유 온라인 상태와 runtime 판정

- 관련 이슈: https://github.com/sor999/rokin/issues/37
- 결정: [ADR 0006](../decisions/0006-shared-presence.md)

## 변경 동작

API의 메모리에 있던 마지막 수신 시각과 runtime을 제거했다. Kafka를 소비한 API가
`robot_presence`에 관측을 저장하고 모든 API는 같은 DB에서 온라인 여부를 판단한다.
DB의 최신 runtime, 삭제 의도, RUNNING 준비 보고, 10초 heartbeat와 기본 30초 관측
유효기간을 함께 검사한다. API 재시작과 Redis 재연결은 공유 관측을 초기화하지 않는다.

heartbeat를 기록하고 만료를 판단하는 기준도 DB 시간으로 통일했다. 로봇 timestamp는
DB와 동기화되어야 하며 5초 초과 미래 시각과 30초 초과 과거 관측은 실시간 경로에서
제외한다. Worker의 원본 이력 적재는 유지한다. 중복 관측은 최대 timestamp를 보존해
유효기간을 새 수신 시각으로 연장하지 않는다.

DB 장애 시 명령을 접수하지 않는다. REST는 503, STOMP는 기존 오류 경로를 사용한다.
주기적 온라인 검사 실패와 실시간 수신 실패는 SSE를 닫는다. 화면은 연결 오류와
오프라인을 표시하고 복구 후 REST를 다시 읽는다. HTTP 503으로 닫힌 SSE도 2초 간격으로
명시적으로 재연결한다. 이전 연결의 콜백과 화면 종료 후 재시도는 차단한다. 조회 중 도착한 SSE는 늦은 스냅샷이
덮어쓰지 않는다. 정적 로봇도 공유 온라인 관측이 있어야 명령을 접수한다.

## 자동 검증

- Java 25에서 API 전체 테스트와 `bootJar` 실행
- 격리 PostgreSQL 15에 Flyway V1~V8을 적용해 실제 SQL과 인덱스 검증
- 서로 다른 서비스 객체와 재시작한 객체가 같은 DB 관측으로 명령 판정 일치
- 만료, 미래 시각, 지연 및 중복 관측, 16개 동시 갱신의 단일 행과 최대 시각 보존
- runtime 교체, 준비 전, heartbeat 만료, 삭제 후 이전 관측의 명령 허용 차단
- 공유 저장소 연결 실패 시 503과 Kafka 명령 미발행
- 1일 지난 관측 정리와 현재 관측 및 등록 보존
- Dashboard 연결 오류 시 온라인 해제, 같은 timestamp 스냅샷 복구 및 SSE 경쟁 검증

PostgreSQL 전용 검증은 전용 테스트 DB에 임시 스키마를 만들고 정리한다.

```bash
PRESENCE_TEST_URL=jdbc:postgresql://localhost:25436/review36 \
FENCE_TEST_URL=jdbc:postgresql://localhost:25436/review36 \
./gradlew test bootJar
```

위 명령은 `services/api`에서 실행한다. 기본 계정은 격리 테스트용 `postgres`,
비밀번호는 `rokin_test_only`이며 `PRESENCE_TEST_USER`, `PRESENCE_TEST_PASSWORD`로 바꿀 수 있다.
기본 H2 실행에서는 PostgreSQL 전용 테스트를 건너뛴다. 일반 H2 성공만으로 실제 SQL과
동시성을 검증했다고 판단하지 않는다.

## 환경 검증

격리된 `rokin-scale-lab`에서 API 2대, Redis, PostgreSQL, Kafka, Worker와 실제 ROS 관리
런타임을 연결한다. `RUN_REALTIME_INTEGRATION=1`에서만 컨테이너 중단과 재시작을 수행한다.
개발 DB와 운영 배포에는 적용하지 않으며 볼륨을 삭제하지 않는다.

```bash
scripts/scalability/lab.sh up dual
RUN_REALTIME_INTEGRATION=1 python3 -m unittest discover \
  -s scripts/scalability -p 'test_realtime_integration.py' -v
```

- API 재시작 중 다른 API의 전달과 완료 Ack 유지
- Redis 단절 시 양쪽 연결 종료와 알려진 Ack REST 복구
- PostgreSQL 단절 시 양쪽 조회 실패와 SSE 종료, 복구 후 양쪽 온라인과 명령 완료
- ROS runtime 재시작 후 양쪽 API가 새 runtime과 온라인 상태로 수렴하고 명령 완료

ROS 강제 재시작은 기존 명령 consumer의 기본 session timeout 약 45초에 영향을 받는다.
실제 첫 확인에서는 약 45초 후 구독 준비가 완료되었다. API 재연결의 30초 기준과 구분해
ROS 복구 검증에는 기존 session 만료와 기동 여유를 합한 60초 상한을 둔다. 이 단계에서
ROS 장애 인계나 Kafka timeout을 변경하지 않는다. 단일 runtime 장애 인계는 5단계 범위다.

최종 실행 결과와 브라우저 확인은 아래에 기록한다. 이번 단계에서는 120초 부하 기준선을
다시 측정하지 않는다. DB 쿼리 비용과 쓰기 최적화는 3단계에서 같은 부하로 비교한다.

## 범위와 한계

하나의 Fleet을 공유하는 API 복제 배치를 지원한다. Kafka 입력 group과 원본 Fleet 분리의
제한은 ADR 0005를 유지한다. Fleet당 ROS 관리 런타임 하나 제한도 유지한다.
판정 시점 뒤 소유권이 바뀌는 경쟁은 기존 명령 버전과 runtime 검증으로 실행을 차단한다.
실제 로봇의 시계 오차가 허용 범위를 넘으면 보수적으로 오프라인으로 판단한다.

## 실행 기록

- API: 73개 테스트, 실패 0개, 기존 Ack 마이그레이션 전용 3개 건너뜀.
  이번 PostgreSQL presence 7개와 기존 fence 및 Flyway timeout 검증은 실제 DB에서 실행했다.
- Dashboard: 24개 테스트, typecheck와 production build 성공.
- Worker: 기존 빌더 컨테이너에서 `go test ./...`, `go vet ./...` 성공.
  Worker의 별도 PostgreSQL 전용 적재 검증은 실행하지 않았다.
- ROS: 로봇 13개, Bridge 42개 테스트 통과. 별도 Kafka 및 ROS 통합 환경을 요구하는 3개는 건너뜀.
  Python compileall 성공. 위 환경 검증은 실제 관리 runtime과 ROS를 사용한다.
- 측정기: 24개 테스트, 기본 실행에서 환경 통합 4개만 건너뜀, 실패 0개.
- 최종 API 2대 환경에서 API 재시작, Redis 단절, DB 단절 시나리오 성공.
  ROS 검증에 API의 30초 기준을 적용한 첫 실행은 실패했다. 기존 Kafka 세션 만료를 확인한 뒤
  ROS만 독립된 60초 상한으로 재검증했으며, 45.265초에 복구하고 양쪽 명령의 완료 Ack를 받았다.
- 120초 부하 측정과 Worker의 PostgreSQL 적재 경쟁 검증은 이 단계에서 반복하지 않았다.
- 브라우저 Fleet에서 3대 온라인을 확인한 뒤 격리 DB를 중단해 연결 오류, 0대 온라인,
  등록과 삭제 버튼 비활성화를 확인했다. DB 복구 후 새로고침 없이 Live와 3대 온라인으로
  자동 복구하는 것을 확인했다.
- 검증 종료 후 격리 서비스는 정지하며 데이터와 볼륨은 보존한다.

## PR 검토 후 보완

- V8에 원본 관측 시각을 추가하고 미래 관측의 중복 수신이 최초 유효기간을 연장하지 않도록 수정했다.
- 관측과 heartbeat 만료는 트랜잭션 시작 시각 대신 SQL 판정 시점의 단일 clock_timestamp()로 비교한다.
- 준비 전 또는 heartbeat 만료 시 SSE 발행을 차단해 REST와 화면의 온라인 기준을 맞췄다.
- 오프라인에 원본 관측 시각을 전달하고 화면의 최신 관측과 pose가 지연 입력으로 역행하지 않게 했다.
- 복구 중 수신한 상태와 배터리를 보존하고, 실제 적용된 SSE만 복구 보호 집합에 포함한다.
- REST 복구 실패는 연결 재시도와 분리된 2초 타이머로 다시 조회한다.
- API 78개 테스트 중 기존 전용 3개 제외 성공. PostgreSQL presence 10개와 기존 fence 및 Flyway timeout은 실제 DB에서 실행했다.
- Dashboard 29개 테스트, typecheck와 build 성공.
- 같은 관측 세대의 반복 오프라인 전송 억제는 새 온라인 전이와 재구독 복구를 함께 다뤄야 하므로 3단계 최적화에 남긴다.
- 실제 DB 중단 구간의 명령 전송은 별도 환경 검증으로 남긴다. 저장소 연결 실패 시 Kafka 명령 미발행은 PostgreSQL 서비스 테스트에서 검증했다.
- API 2대의 네 가지 복구 시나리오를 수정 후 다시 실행해 103.365초에 모두 통과했다.
- V9 기본값으로 구버전 API insert의 호환성을 유지하고 PostgreSQL 테스트로 검증했다.
