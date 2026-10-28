# 검증 컨벤션

## 서비스별 명령

변경한 서비스 디렉터리에서 해당 명령을 직접 실행합니다.

```bash
cd services/api
./gradlew test
```

```bash
cd services/worker
go test ./...
go vet ./...
```

```bash
cd services/dashboard
pnpm typecheck
pnpm build
```

```bash
PYTHONPATH=ros/src/fake_robot python3 -m unittest discover -s ros/src/fake_robot/test -v
python3 -m compileall -q ros
```

- API 테스트는 H2 설정과 Java 25를 사용합니다.
- Worker 로컬 검증에는 `librdkafka`가 필요합니다.
- Dashboard 검증에는 설치된 `node_modules`가 필요합니다.
- `infra/`, `contracts/`, `.env.example`처럼 공통 경계를 바꾸면 영향을 받는 모든 서비스 명령을 실행합니다.

## 기본 게이트 밖의 검증

다음 검증은 느리거나 로컬 상태를 사용하므로 자동 기본 게이트와 분리합니다.

- Docker 통합: `./scripts/dev.sh up` 후 Dashboard/API/Kafka/PostgreSQL 상태 확인
- Worker 컨테이너 테스트: `./scripts/test-worker.sh`
- ROS2 동작: `docker compose -f infra/docker-compose.yml up fake_robot --build` 후 토픽과 Ack 확인
- 브라우저 시각/상호작용: Fleet 목록, 상세 화면, 명령 패널을 실제 브라우저에서 확인
- 파괴적 초기화: `scripts/reset-test-env.sh`는 볼륨을 삭제하므로 명시적 승인 후 실행

기능 변경의 최종 보고에는 실행한 명령, 생략한 환경 의존 검증과 이유를 모두 남깁니다.

## 시나리오 노트가 필요한 변경

둘 이상의 서비스에 걸친 기능, 새 Command/Ack 상태, DB 스키마, 외부 연동, 사용자 흐름을
바꾸는 작업은 구현 전에 다음을 짧게 기록합니다.

| 시나리오 | 준비 상태 | 자동 검증 | 수동 검증 |
| --- | --- | --- | --- |
| 정상 흐름 | 필요한 fixture/서비스 | 테스트 또는 smoke 명령 | UI/토픽 기대 결과 |
| 중복/재전송 | 동일 식별자 재사용 | 멱등성 단언 | 중복 동작 없음 |
| 실패/재연결 | 연결 종료/잘못된 payload | 오류 상태 단언 | 복구와 표시 확인 |

## Ack 스키마와 영속성 PostgreSQL 검증

운영·개발 DB와 분리된 PostgreSQL 15 테스트 DB에만 연결합니다. 테스트가 임시 스키마를
생성하고 정리하므로 해당 DB 계정에 스키마 생성 권한이 필요합니다.

- API: `services/api`에서 `MIGRATION_TEST_URL=jdbc:postgresql://localhost:25417/rokin_issue17 ./gradlew test --tests '*AckMigrationTest' --rerun-tasks`
- Worker: `services/worker`에서 `TEST_DATABASE_URL=postgres://postgres:rokin_test_only@localhost:25417/rokin_issue17?sslmode=disable go test ./internal/repository -v`
- 위 연결값은 격리 테스트용 예시입니다. API는 `MIGRATION_TEST_USER`, `MIGRATION_TEST_PASSWORD`로 계정을 바꿀 수 있습니다. 기본값은 `postgres`, `rokin_test_only`입니다.
- 환경 변수가 없으면 PostgreSQL 전용 테스트는 건너뜁니다. 일반 H2 테스트 통과만으로 마이그레이션과 PostgreSQL 동시성을 검증했다고 보고하지 않습니다.
