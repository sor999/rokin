# 확장성 0단계: 격리 재현 환경과 측정

- 관련 이슈: https://github.com/sor999/rokin/issues/33
- 상위 계획: [분산 서버 확장성 개선 계획](distributed-scalability-plan.md)
- 범위: API 1대/2대 비교 환경과 부하·판정 기준. 전달 경로와 소유권의 수정은 후속 단계에서 수행한다.

## 실행 구조와 격리

기존 Compose에 `infra/scalability/compose.yml`을 적용한다. 프로젝트 이름은
`rokin-scale-lab`으로 고정하고 모든 고정 container_name과 기존 호스트 포트 매핑을 제거한다.
DB·Kafka·네트워크·볼륨은 개발 프로젝트와 분리한다. 기존 볼륨을 삭제하거나 초기화하지 않는다.
내부 토큰은 실행 시 메모리에서 생성하며 결과 파일·문서·명령 출력에 포함하지 않는다.
모드 전환 시 API와 관리 런타임이 토큰을 함께 갱신하고 로봇은 시작 위치에서 복구한다.

| 진입점 | 주소 | 용도 |
| --- | --- | --- |
| 로드밸런서 | http://localhost:18080 | API에 round robin 전달, 응답의 X-Scale-Upstream으로 실제 서버 확인 |
| API A / B | http://localhost:18081 / http://localhost:18082 | 연결을 각 서버에 고정해 전달 차이 측정 |
| Dashboard | http://localhost:13000 | 로드밸런서를 통한 브라우저 확인 |
| Prometheus | http://localhost:19090 | API별·Worker·DB·Kafka 지표 |
| Grafana | http://localhost:13001 | 기존 대시보드 확인 |

모든 공개 포트는 loopback에만 바인딩한다. API B는 dual 모드에서만 실행하며 ROS 관리
런타임은 두 모드 모두 하나다. API consumer group은 바꾸지 않아 현재 전달 결함을 재현한다.
Nginx는 SSE 버퍼링을 끄고 WebSocket upgrade를 전달한다. 로드밸런서 자동 재시도는 꺼서
실패를 측정에서 숨기지 않는다. headless 측정은 각 API에 직접 연결하고, 브라우저는
로드밸런서를 통해 별도로 확인한다.

SockJS의 여러 HTTP 요청은 session ID를 기준으로 같은 API에 전달한다. 이 세션 고정은
연결 유지용이며 Kafka 소비 서버와 SSE 연결 서버의 불일치를 해결하지 않는다.
Dashboard의 브라우저용 `NEXT_PUBLIC_API_URL`은 호스트의 18080을, 서버 rewrite용
`API_INTERNAL_URL`은 Docker 내부 `http://load-balancer:8080`을 사용한다. 후자를 생략하면
기존 공개 API 주소로 fallback하므로 기본 실행 방식은 유지된다.

## 사용자·메시지 흐름과 사전 조건

- 사용자: 환경 실행 → 프로필 선택 → 로봇 준비 확인 → 단일/다중 API 측정 → 결과 비교.
- 관측: 실제 ROS 로봇 → Bridge → Kafka → API별 SSE → 고정 연결 클라이언트.
- 명령: 클라이언트 → SockJS/STOMP → API → Kafka → ROS 정지 → Ack → API별 구독.
- 적재: 같은 Kafka 이벤트를 Worker가 PostgreSQL에 적재한다.
- 필요 도구: Docker Compose의 `!reset`/`!override` 지원 버전, Python 3.9 이상.
  측정 스크립트는 Python 표준 라이브러리만 사용한다.
- 브라우저 수 대신 같은 프로토콜의 headless 클라이언트 수를 자동 부하로 사용한다.
  각 클라이언트는 SSE 한 개와 SockJS XHR streaming STOMP 세션 한 개를 가진다.
  실제 브라우저 렌더링 성능과 native WebSocket 성능을 측정했다고 해석하지 않는다.

## 고정 부하 프로필

정의 파일은 `scripts/scalability/profiles.json`이다. 관리 로봇의 현재 기본 발행 주기인
2Hz를 유지한다. 로봇마다 pose/battery/status가 발행되므로 예상 telemetry는 로봇당 초당 6건이다.
명령은 전달·Ack 측정을 위해 `stop`으로 통일하며 이동 성능은 이 단계에서 다루지 않는다.
각 클라이언트는 초기 로봇 목록을 한 번 조회하고 등록 목록을 2초마다 조회한다.

| 프로필 | 로봇 | 동시 클라이언트 | 측정 시간 | 전체 명령 빈도 | 예상 telemetry |
| --- | --- | --- | --- | --- | --- |
| smoke | 3 | 4 | 30초 | 5초마다 1회 | 18건/초 |
| baseline | 3 | 20 | 120초 | 2초마다 1회 | 18건/초 |
| fleet10 | 10 | 20 | 120초 | 2초마다 1회 | 60건/초 |

명령 발신자와 대상 로봇은 순환한다. 연결 준비 뒤 3초를 기다리고 측정을 시작하며, 끝나기
5초 전부터 신규 명령을 보내지 않는다. 종료 후 2초 동안 마지막 이벤트를 수집한다.
기본 3대는 재사용하며 추가 ID는 고정한다. 프로필 밖의 로봇·삭제 이력이 있으면 측정을
중단한다. 큰 프로필에서 작은 프로필로 돌아갈 때 자동 삭제하지 않는다.

## 판정 기준

아래 값은 0단계에서 정한 로컬 회귀 검증 기준이며 운영 SLO나 검증된 최대 용량이 아니다.
단일/다중 모드에 같은 기준을 적용하고 실패를 맞추기 위해 기준을 완화하지 않는다.

| 항목 | 기준 | 측정 방식 |
| --- | --- | --- |
| 연결 | 준비 성공, 측정 중 오류 0건 | SSE/SockJS 연결·프로토콜 오류 |
| 이벤트 생성량 | 예상 생성량의 90% 이상 관측 | 모든 클라이언트가 관측한 고유 이벤트의 합집합 |
| 클라이언트별 전달률 | 각각 99% 이상 | 각 클라이언트 고유 이벤트 수 / 관측 합집합 크기 |
| 명령 접수율 | 99% 이상 | requestId가 일치하는 cmd-result 수 / 전송 시도 수 |
| 종료 Ack 전달률 | 모든 클라이언트에서 각각 99% 이상 | 접수된 cmdId별 done 수 / 접수 명령 수 |
| 이벤트 지연 | 모든 클라이언트 p95 2초 이하 | 수신 시각 - 이벤트 timestamp |
| Ack 지연 | 수신된 종료 Ack p95 5초 이하 | done 수신 시각 - 명령 전송 시각 |
| REST 지연 | 등록 목록 조회 p95 1초 이하 | 클라이언트의 요청·응답 시각 |
| 관측 지표 | Kafka lag, 버퍼, Worker 메모리, API heap, DB 연결 지표 존재 | Prometheus range query |

합집합 기준만으로 모든 서버에서 동시에 누락된 이벤트를 알 수 없으므로 예상 생성량 기준을
함께 적용한다. SSE에 event_id가 없어서 전체 JSON의 해시로 고유 이벤트를 비교한다.
Ack 지연은 전달률과 함께 판단하며 누락된 Ack를 빠른 응답으로 취급하지 않는다.
송신·수신 시계 차이가 지연에 영향을 주므로 같은 Docker 호스트에서 측정한다.

Kafka lag와 Worker 버퍼는 측정 구간의 최댓값·종료 시점 값을 비교하고, DB 쿼리 수·누적
실행 시간은 전후 차이로 비교한다. DB lock waiters와 미획득 lock은 5초마다 표본을 남긴다.
API heap·프로세스 메모리와 Worker 메모리는 5초 주기로 수집한다. `docker stats`는 종료
시점 스냅샷이므로 peak 메모리로 보고하지 않는다. 부하 도구 자체와 통계 조회 비용도 포함된다.
API별 JVM 메모리 합계와 처리량을 함께 비교하며, DB·Kafka 자원은 두 실행에서 동일하게 유지한다.
장애 복구 시간의 초기 회귀 기준은 건강한 연결 재설정과 상태 수렴까지 30초 이내로 두며,
실제 장애 주입과 이 기준의 검증은 단계 1~2의 복구 테스트에서 수행한다.

## 실행과 결과 저장

저장소 루트에서 실행한다. 모드 전환은 접속을 종료하므로 진행 중 측정을 먼저 끝낸다.
결과는 Git에서 제외된 `tmp/`에 저장한다. 단일/다중 실행 사이 코드와 프로필을 바꾸지 않는다.
측정과 `up`·`stop`·`build`는 같은 파일 잠금을 사용한다. 다른 작업이 진행 중이면 즉시
실패하며 결과 저장까지 끝난 뒤 다시 실행해야 한다. 브라우저 확인은 성능 측정과 분리한다.

```bash
scripts/scalability/lab.sh config single
scripts/scalability/lab.sh config dual
scripts/scalability/lab.sh up single
python3 scripts/scalability/measure.py --mode single --profile smoke --output tmp/scalability/single-smoke.json --enforce
scripts/scalability/lab.sh up dual
python3 scripts/scalability/measure.py --mode dual --profile smoke --output tmp/scalability/dual-smoke.json
python3 scripts/scalability/compare.py tmp/scalability/single-smoke.json tmp/scalability/dual-smoke.json
```

기본 실행은 측정 결과를 저장한 뒤 정합성 실패도 종료 코드 0으로 반환해 기존 결함의
기준선을 남긴다. `--enforce`를 붙이면 판정 실패는 종료 코드 1이다. 연결 준비·DB·수집 오류는
어느 모드든 실행 실패다. dual에서 현재 결함이 검출되는 것은 환경 구성 실패와 구분한다.
smoke가 끝나면 두 모드에서 `--profile baseline`으로 같은 비교를 반복한다.
정식 비교는 각각 3회 실행하고 실행별 결과와 분산을 함께 보존한다.

```bash
scripts/scalability/lab.sh status
scripts/scalability/lab.sh stop
```

`stop`은 컨테이너를 정지하고 데이터를 보존한다. 볼륨 삭제 명령은 제공하지 않는다.

JSON에는 프로필·판정·클라이언트별 전달률·지연, 명령 오류율, Git revision·dirty 상태,
DB 통계 전후 값·표본, Prometheus 시계열, 종료 시점 컨테이너 자원을 기록한다.
로그인 토큰·환경 변수 전체·DB 쿼리 원문은 수집하지 않는다.

## 자동·수동 검증

| 시나리오 | 자동 검증 | 수동 검증 |
| --- | --- | --- |
| 단일 API | 이벤트·Ack 전달률과 지연 | Fleet 표시와 정지 확인 |
| API 2대 | 서버별 고정 연결에서 누락·명령 접수 차이 측정 | Nginx 응답 헤더와 서로 다른 API의 이벤트 비교 |
| 측정기 오판 방지 | 분할 전달, 누락 Ack, 미접수 명령, 빈 이벤트, 지연·단절 단위 테스트 | 저장 JSON과 실제 표시 비교 |
| 재실행 | 같은 ID·설정의 로봇 재사용 | 모드 전환 후 시작 위치·등록 목록 확인 |

브라우저에서 `http://localhost:13000/fleet`을 열고 개발자 도구 Network에서 SSE의
`X-Scale-Upstream`을 확인한다. 각 서버에 연결을 고정해 비교하려면 별도 탭에서
`http://localhost:18081/api/stream/telemetry`와 `http://localhost:18082/api/stream/telemetry`를
연다. 두 스트림에서 같은 로봇·종류가 계속 보이는지 확인한다. Dashboard에서 정지한 뒤
Ack가 표시되는지도 확인한다. 이 절차는 수동 검증이며 자동 결과만으로 완료 처리하지 않는다.

측정기 테스트:

```bash
python3 -m unittest discover -s scripts/scalability -p 'test_*.py' -v
```

서비스별 필수 검증은 [검증 컨벤션](../conventions/verification.md)을 따른다.

## 실행 결과

실제 실행 후 환경, 프로필, 단일/다중 결과와 한계를 아래에 기록한다.

2026-10-30, Docker 29.8.2 / aarch64 / 11 vCPU / 약 7.75 GiB 환경에서 실행했다.
아래는 브라우저를 닫고 `baseline` 프로필로 각 모드 1회씩 실행한 재현 결과다.
별도의 `smoke` 프로필에서도 같은 전달 차이를 확인했다. 정식 3회 반복 용량 벤치마크와
fleet10 부하는 수행하지 않았으며 지원 가능한 최대 규모를 선언하지 않는다.

| 항목 | API 1대 | API 2대 |
| --- | --- | --- |
| 고유 이벤트 합집합 | 2,160건 | 2,160건 |
| 클라이언트별 이벤트 전달률 | 모두 100% | A 33.3%, B 66.7% |
| 명령 접수 | 58/58건 | 58/58건 |
| 클라이언트별 종료 Ack 전달률 | 모두 100% | A 100%, B 0% |
| 가장 느린 클라이언트 이벤트 p95 | 31.1ms | 44.2ms |
| 수신된 종료 Ack p95 | 41.1ms | 100.1ms |
| 등록 목록 REST p95 | 34.6ms | 41.6ms |
| DB 쿼리 수 | 101.7건/초 | 102.0건/초 |
| DB 평균 실행 시간 | 0.073ms | 0.118ms |
| 관측된 DB lock waiters 최대 | 0 | 0 |
| 관측된 Kafka partition lag 최대 | 2 | 2 |
| 판정 | 전체 기준 통과 | 이벤트·종료 Ack 전달률 실패 |

API 2대에서는 합집합 기준으로 생성량을 모두 관측했지만 각 연결은 일부만 수신했다.
이는 연결 실패나 명령 미접수와 구분되는 실시간 fan-out 결함의 기준선이다.
수신된 데이터의 지연이 작아도 전달률 실패를 상쇄하지 않는다. 실제 partition 배정에 따라
서버별 비율은 달라질 수 있으므로 위 비율 자체를 회귀 테스트 기대값으로 고정하지 않는다.

원시 결과와 비교 파일은 실행한 작업 공간의 다음 위치에 보존한다.

- `tmp/scalability/single-smoke.json`, `tmp/scalability/dual-smoke.json`
- `tmp/scalability/single-baseline.json`, `tmp/scalability/dual-baseline.json`
- `tmp/scalability/comparison-baseline.json`

검증 결과:

- 측정기 단위 테스트 10개 통과. 별도 프로세스의 잠금 충돌·해제와 가변 시간 정밀도 포함.
- 두 Compose 모드 설정 검증 및 API·Worker·ROS·Dashboard 이미지 빌드 통과.
- API `./gradlew test bootJar` 통과. 36개 중 PostgreSQL 전용 테스트 3개는 환경 변수 미지정으로 건너뜀.
- Worker 현재 소스를 기존 builder 이미지에 마운트해 `go test ./...`, `go vet ./...` 통과.
- Dashboard `pnpm test`, `pnpm typecheck`, `pnpm build` 통과.
- FakeRobot 단위 테스트와 ROS compileall 통과. Bridge는 confluent-kafka가 있는 기존 ROS 이미지에서
  현재 소스 테스트 42개 실행, 별도 Kafka/ROS 환경 변수가 필요한 6개를 건너뜀.
- 이번 격리 환경에서 실제 ROS → Kafka → API SSE, 정지 → ROS → Ack 경로를 측정함.
  이를 별도 전체 통합 테스트 모음의 통과나 Worker 멱등성·장애 복구 검증으로 확대 해석하지 않음.
- 브라우저에서 등록 로봇 3대의 실행 중·온라인 표시와 등록 버튼 활성화를 확인함.
- 로드밸런서를 통한 SSE·SockJS 세션 및 정지 Ack 확인. 브라우저 렌더링 성능은 미측정.
- API 2대 로드밸런서에서도 4개 SockJS 세션의 연결·명령 접수 4/4건을 확인함.

제품의 이벤트 전달·온라인 판정·로봇 소유권 계약은 후속 단계에서 변경한다.
0단계의 테스트 배치와 측정 기준은 이 문서에서 관리하며 새로운 제품 ADR은 추가하지 않았다.
