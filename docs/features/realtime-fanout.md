# API 간 실시간 전달

- 관련 이슈: https://github.com/sor999/rokin/issues/35
- 계약: [ADR 0005](../decisions/0005-realtime-fanout.md)

## 사용자·메시지 흐름

Kafka 소비 API → Fleet Redis 채널 → 모든 API → 각 API의 SSE·STOMP 연결로 전달한다.
기존 Kafka consumer group은 유지한다. 브라우저의 EventSource와 SockJS 주소 및 이벤트·Ack
필드는 유지하며 Redis 주소와 비밀번호는 서버 안에서만 사용한다.

기본 Compose는 Redis relay와 세션별 STOMP 수신 순서를 활성화한다. 단독 JVM/H2의 기본은
local 모드이며 API 복제 운영에는 사용하지 않는다. 같은 Kafka 입력 그룹의 모든 API는
같은 Fleet을 사용해야 한다. 다중 Fleet 입력 분리는 이번 단계의 지원 범위가 아니다.

## 준비 상태와 장애

- 연결·구독과 전용 probe의 왕복을 확인한 뒤 전달 경로를 준비 완료로 표시한다.
- 2초 주기 probe를 6초 넘게 수신하지 못하거나 발행이 실패하면 경로를 닫고 재연결한다.
- 단절 시 기존 SSE와 WS를 닫고 로컬 online 캐시를 비운다. 준비 전 SSE와 명령 요청은 503으로 거부한다.
- `/actuator/health/readiness`는 relay 상태를 포함한다. Redis 장애로 liveness를 실패시키지는 않는다.
- 화면은 SSE 재접속 때 REST 로봇 목록을 다시 읽으며, 오래된 연결의 응답은 취소한다.
- 자신이 보낸 requestId의 접수 응답으로 cmd_id를 확인한 명령만 1초 간격 REST 조회로 복구한다.
  `GET /api/robots/{robotId}/acks/{cmdId}`는 아직 적재되지 않았으면 404, 값이 있으면 기존 Ack DTO를 반환한다.
  다른 Fleet의 등록 로봇 조회는 404다. Worker의 전이·적재 순서를 사용하며 이벤트 시각 역행으로 상태를 고르지 않는다.
- 접수 응답을 못 받은 명령은 식별자를 추측하거나 이동을 재전송하지 않는다. 결과 미확인 상태에서 사용자가 판단한다.

Redis pub/sub는 영속 큐가 아니다. live 연결에 전달하지 못한 관측·명령 결과는 DB의 현재 상태와
알려진 명령의 Ack로 보완한다. 브라우저 전체 재시작 후의 명령 식별자 복원은 제공하지 않는다.
Redis 단일 인스턴스는 개발 구성이다. 운영 HA·TLS와 최대 처리량은 별도 인프라 검증 대상이다.

## 제한과 후속 단계

- Redis 수신 대기 1024건, 내부 메시지 최대 64Ki 문자, 중복 창은 10,000건/5분으로 제한한다.
- SSE 기본 최대 1000개 전송 작업, 연결별 최대 32개의 대기 키, 전송 시간 상한 5초다.
  같은 로봇·종류의 대기 관측은 최신 값으로 합친다. 정리 작업도 전송과 분리한다.
- WebSocket 최대 1000연결, 전송 버퍼 64KiB/5초 및 outbound 실행 큐 256건으로 제한한다.
  Ack 경로가 포화되면 연결을 닫아 REST 복구를 유도한다. 지속 가능한 연결 수를 뜻하는 값은 아니다.
- 모든 API의 정상 수신으로 로컬 온라인 캐시는 갱신되지만, 권위 있는 온라인 상태 공유는 2단계다.
- runtime/삭제 fencing을 수신 노드에서도 확인하므로 API 수에 따라 DB 조회가 늘어난다. 3단계에서 최적화한다.

## 검증

```bash
python3 -m unittest discover -s scripts/scalability -p 'test_*.py' -v
scripts/scalability/lab.sh up dual
RUN_REALTIME_INTEGRATION=1 python3 -m unittest discover -s scripts/scalability -p test_realtime_integration.py -v
python3 scripts/scalability/measure.py --mode dual --profile baseline --output tmp/scalability/stage1-dual-baseline.json --enforce
```

통합 테스트는 격리 `rokin-scale-lab`에만 연결한다. Redis 및 API A를 정지·재시작하고 마지막에
복구하며 볼륨을 삭제하지 않는다. 일반 테스트에서는 명시적 환경 변수가 없으면 건너뛴다.
부하 측정과 통합 테스트는 같은 잠금을 사용하므로 동시에 실행하지 않는다.

| 시나리오 | 검증 |
| --- | --- |
| 정상 API 2대 | 모든 연결에 같은 관측·Ack, 기존 cmd_id별 명령 접수 |
| 중복·지연·과거 runtime | 중복·종료 뒤 accepted·다른 세대 이벤트 차단 |
| 느린 SSE | 정상 연결 전송 지속, 대기 관측 병합, 시간·버퍼 상한 |
| API 재시작 | 다른 API 관측 유지, 재접속 후 상태·Ack 복구 |
| Redis 단절 | 기존 연결 종료, 준비 전 차단, 재구독·현재 상태 30초 내 수렴 |
| UI 재연결 | 이전 REST 응답 취소, 알려진 요청만 복구, 명령 자동 재전송 없음 |

### 2026-11-02 실행 결과

격리 Docker 호스트 11 CPU / 약 7.75GiB / aarch64 / Engine 29.8.2에서 기본 로봇 3대,
총 18이벤트/초, 연결 20개, 120초, 2초당 정지 명령 1회로 측정했다. 두 모드 모두 58건을
접수했고 모든 연결의 이벤트·최종 Ack 전달률이 100%, 명령 오류율이 0%였다.

| 항목 | API 1대 | API 2대 |
| --- | ---: | ---: |
| 판정 항목 | 10/10 통과 | 10/10 통과 |
| 가장 느린 연결의 이벤트 p95 | 27.1ms | 42.5ms |
| 최종 Ack p95 | 35.4ms | 115.6ms |
| DB 쿼리/초 | 137.2 | 173.5 |
| DB 평균 실행 시간 | 0.090ms | 0.082ms |
| 관측 최대 락 대기자 | 0 | 0 |
| API 합산 heap 최댓값 | 102.1MiB | 218.1MiB |
| Kafka lag 최댓값 | 2 | 2 |

2대 실행의 relay 가용 지표는 전 구간 1, 수신 대기 최댓값은 3건, SSE 대기 키 최댓값은
6개였다. 주기적 샘플의 관측값이며 순간 최댓값이나 운영 처리량 상한을 의미하지 않는다.
수신 API의 runtime 재검증으로 DB 조회가 늘어나는 결과를 확인했으며 3단계에서 개선한다.

두 실행은 main `b6b08e087dcbc813fb0b8eb1a4bf7259b5ae627e`에 같은 작업 변경을 적용했고,
소스 fingerprint `710cac11775cc367acff6325095aeda89ca18cfc9dd3114a5deac1df3446a5ff`와
호스트·프로필 일치를 비교기로 확인했다. 측정 뒤에는 결과 문서만 갱신했다.
원시 결과는 로컬 `tmp/scalability/stage1-single-baseline.json`,
`stage1-dual-baseline.json`, `stage1-comparison.json`에 보존한다.

모드 전환 직후에는 Prometheus가 이전 대상의 값을 lookback 구간에 남길 수 있다.
첫 single 실행은 종료된 API B의 과거 DOWN 값으로 `targets_up`만 실패했다.
활성 대상 목록과 잔여 시계열 만료를 확인한 뒤 재측정했으며, 실패 판정을 완화하지 않았다.
볼륨을 유지한 dual→single 재측정은 `/api/v1/targets`와 `up`에서 API B가 사라진 뒤 시작한다
(기본 lookback 최대 5분). DB·Kafka·Prometheus 볼륨은 삭제하지 않았다.

- API: Java 25 `test bootJar` 성공. 51개 중 실패 0, 별도 PostgreSQL 검증 3개 건너뜀.
- Dashboard: `pnpm test` 14개 성공, `pnpm typecheck`, `pnpm build` 성공.
- Worker: 격리 빌더에서 `go test ./...`, `go vet ./...` 성공. 별도 PostgreSQL 환경 변수 미지정.
- ROS: ROS Humble 경로를 유지한 컨테이너에서 FakeRobot 13개, Bridge 42개 중 실패 0.
  별도 Kafka/ROS 통합 환경이 필요한 3개 건너뜀. `compileall` 성공.
- 측정기: 일반 테스트 22개 중 실패 0, 명시적 환경 변수가 필요한 복구 테스트 2개 건너뜀.
- 장애 복구: 최종 이미지에서 2개 통합 테스트 성공(총 40.8초). API A 종료 중 B의 이벤트·REST Ack 유지,
  A 재시작과 Redis 정지·재시작 후 각 30초 이내 현재 상태 수렴 및 양쪽 새 Ack 수신 확인.
  Redis 단절 중 SSE·SockJS 종료, 준비 전 503, 기존 명령 Ack REST 조회도 확인.
- 브라우저: Fleet에서 3대 실행·온라인·위치·배터리, 상세 화면의 Telemetry Live와 WS Connected 확인.
  부하 측정의 명령과 간섭하지 않도록 화면에서 이동·정지는 실행하지 않았다.

대규모 연결, 장기 장애, 실제 네트워크 분할과 운영 Redis HA는 이번 결과의 검증 범위에 포함되지 않는다.
권위 있는 presence 공유인 2단계까지 마쳐야 다중 API 운영을 허용한다.
