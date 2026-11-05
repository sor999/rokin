# Worker 부하 제어와 장애 복구 검증

## 처리 계약

[ADR 0009](../decisions/0009-worker-backpressure.md)에 따라 하나의 프로세스에서
텔레메트리와 Ack Consumer, 예약 예산 및 DB 풀을 분리한다. 두 Consumer는
기존 KAFKA_GROUP_ID를 공유하지만 구독 토픽은 겹치지 않는다. 기존 커밋을 유지한다.
DB 전체가 중단되면 Ack도 대기한다. 별도 경로는 위치 적재 정체의 전파를 막는다.

파티션별로 한 배치만 실행한다. 성공 전 배치를 고정하고 제한된 실행 슬롯을 순환
배정한다. 취소된 이전 할당의 작업은 실제 종료까지 슬롯을 점유한다. 저장 또는
DLQ 전달 확인이 끝난 수신 순서의 접두부까지만 커밋하며 숫자 간격을 가정하지 않는다.
커밋 실패 후 동일하거나 더 높은 완료 위치로 재시도한다.

새 group은 보존 중인 가장 오래된 메시지부터 읽는다. 기존 커밋이 보존 범위를 벗어나면
오류 종료하며 운영자의 확인이 필요하다. 오프셋 초기화나 임의 건너뛰기를 하지 않는다.
원본이 크기 상한을 넘거나 DLQ envelope가 상한을 넘으면 해당 파티션을 중지한다.
원본은 Kafka에 남기며 로그의 topic, partition, offset과 원인으로 확인한다.
해결할 때도 원본 보관과 명시적인 운영 결정을 거쳐야 하며 자동 폐기하지 않는다.

## 설정

| 환경 변수 | 기본값 | 의미 |
| --- | --- | --- |
| TELEMETRY_PENDING_MESSAGES | 4096 | 경로 전체 대기 및 실행 메시지 |
| TELEMETRY_PENDING_BYTES | 67108864 | 텔레메트리 예약 바이트 |
| ACK_PENDING_MESSAGES | 1024 | Ack 경로 대기 및 실행 메시지 |
| ACK_PENDING_BYTES | 16777216 | Ack 예약 바이트 |
| PARTITION_PENDING_MESSAGES | 256 | 파티션별 대기 및 실행 메시지 |
| PARTITION_PENDING_BYTES | 16777216 | 파티션별 예약 바이트 |
| BATCH_SIZE | 100 | 한 배치의 최대 메시지 수 |
| BATCH_BYTES | 1048576 | 한 배치의 원본 합계 |
| BATCH_FLUSH_MS | 500 | 배치 대기 시간 |
| MESSAGE_MAX_BYTES | 1048576 | 원본 메시지 크기 |
| TELEMETRY_DB_CONCURRENCY | 4 | 텔레메트리 실행 및 DB 연결 상한 |
| ACK_DB_CONCURRENCY | 1 | Ack 실행 및 DB 연결 상한 |
| TELEMETRY_KAFKA_QUEUE_KIB | 16384 | librdkafka 텔레메트리 큐 |
| ACK_KAFKA_QUEUE_KIB | 4096 | librdkafka Ack 큐 |
| DB_OPERATION_TIMEOUT_MS | 5000 | DB 배치 및 개별 DLQ 요청 제한 시간 |
| SHUTDOWN_TIMEOUT_MS | 20000 | 전체 종료 제한 시간 |

기존 KAFKA_BROKER_URL, KAFKA_GROUP_ID, KAFKA_TOPICS, DLQ_TOPIC, METRICS_ADDR를
유지한다. 알 수 없는 토픽과 중복 구독, 양수가 아닌 값 및 최대 메시지를 담지 못하는
예산은 시작 시 거부한다. 클라이언트 자체 설정 제약도 적용된다.

메시지 한 건당 원본 크기 8배에 8192바이트를 더해 예약한다. 배치와 재시도 및 DLQ
처리를 포함한다. 최대 메시지 수용 공간이 없으면 Pause하고, 전체와 파티션 사용량이
절반 이하이며 수용 가능해지면 Resume한다. 파티션별 상한 때문에 전체 경로 예산보다
먼저 소비가 중지될 수 있다. Pause 중에도 Poll과 재할당을 처리한다. 새 할당에서는 native Pause 상태를
명시적으로 초기화한다. 애플리케이션의 paused 플래그만 새로 만드는 것으로는 충분하지 않다.

예약량은 RSS와 다르다. Go heap 외에 librdkafka, DB 드라이버와 네이티브 버퍼가 있다.
Kafka 큐 설정도 fetch 응답 크기 때문에 초과할 수 있다. 검증 컨테이너는 512MiB,
GOMEMLIMIT는 320MiB로 설정하고 RSS 및 컨테이너 상태를 별도로 확인한다.

DB 연결 예산은 기본 Worker당 5개, 3대면 최대 15개다. API와 다른 DB 사용자의
연결을 더해 전체 예산을 정한다. DB 작업 실패는 1초부터 최대 5초 백오프로 재시도한다.
정상 종료는 소비 중지와 정리 후 커밋 및 클라이언트 종료 순서다. 전체 제한을 넘으면
프로세스를 종료하고 미커밋 메시지는 다음 실행에서 재처리한다. Compose 유예는 30초다.

## 격리 검증 실행

개발 DB 및 브로커와 다른 rokin-worker-lab 프로젝트를 사용한다. host의 기본 Kafka나
PostgreSQL 포트를 열지 않는다. 새 DB 볼륨의 최초 초기화에서 API 소유 마이그레이션을
번호순으로 적용하며 Worker는 DDL을 실행하지 않는다. 이 DB는 Flyway 운영 전환 검증을
대체하지 않는다. 적용된 마이그레이션이나 기존 볼륨을 다시 쓰지 않는다.

```bash
docker compose --project-name rokin-worker-lab --env-file /dev/null -f infra/worker-lab/compose.yml --profile tools build postgres worker load
docker compose --project-name rokin-worker-lab --env-file /dev/null -f infra/worker-lab/compose.yml up -d --wait --wait-timeout 240 worker
python3 scripts/worker-lab/verify.py --output /tmp/worker-backpressure.json
python3 scripts/worker-lab/rebalance.py --output /tmp/worker-rebalance.json
```

실행은 초당 텔레메트리 500건과 Ack 10건을 유지한다. pose만 잠근 상태의 Ack p95,
DB 600초 중단 중 5초 간격 RSS와 대기량, 복구 후 lag 및 전체 적재 수를 확인한다.
복구 제한은 600초다. 버퍼가 찬 상태에서 복구 후 Worker를 1대에서 3대로 늘리고,
강제 종료 및 정상 축소 후 다시 1대로 수렴시킨다. 원본 이벤트는 UUID로 구분한다.
추가 rebalance.py는 DB가 중단된 상태에서 실제 pause를 확인한 뒤 강제 종료와 정상 축소를
실행한다. 결과 JSON은 최종 전달 확인 건수와 DB 저장 건수를 비교하며 실패 시에도 측정치를 남긴다.
측정 및 환경 변경의 중복 실행은 파일 잠금으로 거부한다.

`--outage-seconds`와 `--recovery-seconds`는 짧은 개발 검증에만 사용한다.
짧은 실행 통과를 10분 장애 통과로 기록하지 않는다. 테스트는 고유한 run의 데이터만
집계하며 이전 실행의 이력이나 consumer group 및 볼륨을 지우지 않는다.

```bash
python3 -m unittest discover -s scripts/worker-lab -p 'test_*.py' -v
docker build --target builder -t rokin-worker-lab-builder services/worker
docker run --rm --network rokin-worker-lab_default \
  -v "$PWD/services:/services" -w /services/worker \
  -e KAFKA_TEST_BROKER=kafka:9092 \
  -e 'TEST_DATABASE_URL=postgres://postgres:worker_lab_only@postgres:5432/telemetry?sslmode=disable' \
  rokin-worker-lab-builder sh -c 'go test ./... && go test -race ./... && go vet ./... && go build -o /tmp/worker ./cmd/worker'
```

Kafka 테스트의 고유 worker-test 토픽과 group은 보존한다. PostgreSQL Repository 테스트는
전용 DB의 임시 스키마 안에서 검증하며 테스트가 만든 스키마만 정리한다.
일반 게이트는 go test ./..., go test -race ./..., go vet ./...와 Worker 빌드다.

## 지표와 확인

- worker_pending: lane별 messages, reserved_bytes, paused_partitions
- worker_partition_paused_seconds_total: lane별 중지 파티션 시간 합계
- worker_control_total: retry, rebalance, blocked
- worker_offset_commit_total: 커밋 성공 및 실패
- worker_partition_lag: 클라이언트가 마지막으로 조회한 high watermark와 커밋 위치 차이
- process_resident_memory_bytes: Go 외 네이티브 메모리를 포함한 RSS

Pause 중에는 cached high watermark가 최신 브로커 위치를 반영하지 못할 수 있다.
검증 도구는 kafka-consumer-groups로 실제 lag를 다시 조회한다. 운영에서도 브로커 기준
exporter의 lag와 함께 확인한다. RSS 외에 Docker의 OOMKilled, RestartCount도 검사한다.

선택적인 Prometheus는 DNS로 Worker 복제본을 찾아 수집한다.

```bash
docker compose --project-name rokin-worker-lab --env-file /dev/null -f infra/worker-lab/compose.yml --profile observability up -d prometheus
# http://localhost:29445 에서 지표와 5개 경보 규칙 확인
docker compose --project-name rokin-worker-lab --env-file /dev/null -f infra/worker-lab/compose.yml --profile observability stop
```

실행 후 stop만 사용해 볼륨과 이력을 남긴다. down -v와 오프셋 재설정은 사용하지 않는다.

## 배포 전환

기존 Worker를 정상 종료한 뒤 같은 group으로 새 Worker를 시작한다. 스키마와 메시지,
REST 및 SSE 계약은 바꾸지 않는다. COMMAND_DURABLE_ENABLED의 기본값은 false다.
새 명령 영속 경로 활성화는 별도 전환 절차를 따른다. Kafka 보존 기간을 넘긴 원본의
복구와 ROS 실행 서버 장애 인계는 범위 밖이다.

## 실행 결과

2026-11-05, Docker Desktop arm64, 11 CPU와 약 7.75GiB 메모리 환경에서 실행했다.
Worker 제한은 각각 512MiB, GOMEMLIMIT 320MiB다. 실제 서버의 장애 영역 분리를
검증한 결과는 아니며 이 부하와 환경에 대해서만 수치를 해석한다.

| 항목 | 결과 |
| --- | --- |
| 유입 | 텔레메트리 500건/초, Ack 10건/초 |
| DB 중단 | 600.003초 |
| 대기량 plateau | 텔레메트리 2304건, Ack 768건 |
| 예약 바이트 plateau | 텔레메트리 23089152, Ack 7434240 |
| Ack 격리 p95 | 0.526509초 |
| 복구 시간 | 59.988초 |
| lag | 장애 전 375, 복구 후 149 |
| 최대 RSS | 104542208바이트, 약 99.70MiB |
| 생성 및 저장 | pose 348232, battery 43529, status 43528, Ack 8705 |
| 합계 | 443994건, 전달 확인 수와 DB 저장 수 일치 |
| 증설 및 축소 | 1대에서 3대로 증설, 강제 종료와 정상 축소 후 1대 수렴 |

OOM과 비의도적인 재시작 없이 통과했다. DB 중단 중 별도 42회 cgroup 측정에서
컨테이너 메모리 최대값은 82145280바이트, 약 78.34MiB였다. 이 cgroup 별도 측정은
장기 시험 전체 구간의 최대값이 아니며 중단 구간의 추가 확인이다.

장기 시험의 핵심 코드 버전은 31b83b4이며 검증 이미지 ID는
97a18108c3a2d8158c14e43b5843328588cfc39e1dce6a439c3ef378c5d2c2df다.
후속 검토에서 개별 DB/DLQ 제한 시간, 잘못된 식별자의 DLQ 처리와 수신 프레임 상한을
보완했다. 최종 코드의 PostgreSQL, 실제 Kafka 및 race 검사를 다시 실행했고,
최종 이미지로 DB 장애 중 가득 찬 버퍼의 회수와 강제 종료를 별도 재검증했다.
첫 추가 시험에서는 살아남은 Consumer의 이전 Pause 상태가 재할당 후 남아
14151건의 lag가 멈춘 것을 확인했다. 원본은 Kafka에 유지됐으며 새 할당의 명시적
Resume과 회귀 테스트로 보완했다. 실패한 시험을 성공 결과에 포함하지 않는다.


추가 시험의 수정 후 재실행은 핵심 코드 abec886과 이미지
36eb5e6b23b846d0b345798c0e0bc8cb136f367048e408abf8c7a5188fcdb205에서 통과했다.
DB 중단 중 세 Worker의 실제 pause를 확인한 뒤 강제 종료 및 1대로 정상 축소했고,
축소 시간은 11.987초였다. 생성한 pose 42406건, battery 5300건, status 5300건과
Ack 1060건, 총 54066건이 모두 저장됐다. 이전 실패 시험의 53698건도 원본에서
회수돼 전달 확인 수와 일치했고 최종 group lag는 0이었다.
추가 시험의 최대 RSS는 72.91MiB, 최대 컨테이너 메모리는 69.64MiB였다.

원본 측정은 /tmp/rokin-worker-stage4.json, /tmp/rokin-worker-cgroup.json,
/tmp/rokin-worker-rebalance.json과 /tmp/rokin-worker-rebalance-fixed.json에 남겼다.
이 파일은 로컬 실행 산출물이며 다른 환경에서는 위 명령으로 새로 생성한다.
