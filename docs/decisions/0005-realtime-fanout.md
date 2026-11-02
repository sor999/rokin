# ADR 0005: API의 공유 실시간 전달 경로

- 상태: 구현·검증 완료
- 관련 이슈: https://github.com/sor999/rokin/issues/35

## 결정과 범위

Kafka의 기존 `api-sse-broadcaster`, `api-ack-broadcaster` 그룹을 유지한다. 그룹에서 소비한
API가 Redis pub/sub의 Fleet 채널에 이벤트를 한 번 발행하고, 모든 API가 이를 받아 자신의
연결에 전달한다. API별 group을 만들지 않아 복제 수에 따른 Kafka 전체 중복 소비와 group
정리 비용을 피한다. 같은 배포의 API는 동일 Fleet 설정을 사용해야 한다. 서로 다른 Fleet의 API를
같은 Kafka 입력 그룹에 섞는 배치는 지원하지 않는다. 여러 Fleet 입력의 분리와 원본 Fleet 식별은 별도 계약이 필요하다.

STOMP broker relay와 Redis를 검토했다. 이번 단계는 Redis를 공유 전달 계층으로 사용하고
각 API의 SimpleBroker는 로컬 연결에만 전달하는 말단으로 둔다. SimpleBroker 자체의
구독 복제에 의존하지 않는다. 외부 STOMP broker는 더 많은 연결의 처리를 외부화할 때
다시 검토한다. Redis는 내부 Docker 네트워크에서만 접근하며 브라우저에 자격 증명을 보내지 않는다.
단독 API 개발과 기본 H2 테스트는 relay 비활성 모드를 유지하고 Compose에서는 활성화한다.
Compose의 세션 수신 순서는 SUBSCRIBE 뒤 SEND가 역전되지 않도록 보장한다.

## 메시지와 실패 계약

- 내부 envelope에 버전, Fleet, 종류, 식별자와 기존 DTO를 포함한다. status state는 256자 이하로 검증한다. SSE/WS의 공개 필드는 유지한다.
- telemetry의 event_id, Ack의 cmd_id+status로 제한된 시간·크기의 중복 창을 적용한다.
- 현재 runtime과 삭제 의도 검증은 수신 API에서도 수행한다. DB 검증 조회의 증폭은 3단계의 최적화 대상이다. 캐시로 판정을 늦추지 않고
  fence 트랜잭션은 1초, DB 풀 획득은 1초, PostgreSQL 소켓 무응답은 2초로 제한한다.
  DB 지연이 지속되면 큐 상한 정책으로 보수적으로 연결을 닫는다.
- Redis의 구독과 발행 연결이 준비된 후에만 실시간 경로를 사용한다. 단절·처리 큐 초과는 연결을
  닫고 로컬 online 캐시를 비운다. 준비 전 명령은 거부하며 과거 명령을 자동 재전송하지 않는다.
- 재연결은 재구독과 전용 probe 응답 확인 후 준비 상태로 전환한다. 2초 간격 probe가 6초 동안
  수신되지 않으면 다음 점검에서 단절로 처리한다. 오래된 연결 세대의 대기 작업은 적용하지 않는다.
- pub/sub와 로컬 전송은 영속 로그가 아니다. Redis가 수신했어도 개별 화면 수신을 보장하지 않는다.
  중단 중 텔레메트리·Ack는 Worker/DB에 보존되며 화면은 REST로 현재 상태와 알려진 cmd_id의 Ack를 복구한다. 동시에 최대 100개의 미종료·미확인 요청을 추적하고
  그 이상 새 전송은 받지 않는다. REST 복구는 요청별 5분으로 제한하며 이후의 알려진 Ack는 STOMP로 반영한다.
  cmd_id 접수 응답 자체를 못 받은 명령은 결과 미확인으로 남기고 이동을 자동 재시도하지 않는다.
- 영속 상태와 온라인 판정의 권위 있는 공유 저장소는 2단계다. 이번 로컬 캐시는 모든 API에 갱신되지만
  새 노드는 첫 현재 이벤트 전까지 보수적으로 오프라인으로 취급한다.

## 부하 제한

Redis 수신 처리 큐와 연결별 대기 버퍼에 상한을 둔다. SSE는 연결별 독립 전송 작업을 사용하고
동일 로봇·종류의 대기 관측은 최신 값으로 병합한다. 전송 시간이나 버퍼 상한을 넘는 연결은
닫아 재접속·REST 복구를 유도한다. WebSocket의 전송 버퍼·시간과 outbound 실행 큐도 제한한다.
Tomcat NIO의 소켓 무진행 쓰기 제한도 5초로 두어 interrupt만으로 끝나지 않는 전송을 종료한다.
writer 슬롯은 실제 전송·정리 작업이 끝난 뒤 회수해 차단된 작업이 무제한 누적되지 않게 한다.
Ack는 위치처럼 병합하지 않고, 로컬 전달 경로가 막히면 해당 노드의 연결을 끊어 결과 복구를 유도한다.

## 구현 전 시나리오

| 시나리오 | 자동 검증 | 실제 환경 검증 |
| --- | --- | --- |
| API 2대 | 두 수신기의 같은 이벤트·Ack 전달 | 0단계 smoke와 baseline의 전달률 비교 |
| 중복·잘못된 payload·과거 runtime | 중복 창과 역행·입력 검증 | 등록·삭제 뒤 늦은 이벤트 확인 |
| 느린 SSE 연결 | 막힌 연결과 정상 연결을 분리하고 버퍼·시간 상한 검증 | 정상 연결 지연 유지 |
| Redis 단절·API 재시작 | 연결 세대와 cache 정리 및 명령 차단 | 재구독·REST 복구와 30초 이내 수렴 |
| Ack 복구 | 알려진 명령만 조회하며 이전 요청 응답 무시 | Worker DB 적재 후 재연결 |

## 참고

- [Spring 외부 broker와 SimpleBroker 범위](https://docs.spring.io/spring-framework/reference/web/websocket/stomp/handle-broker-relay.html)
- [Redis pub/sub 전달 의미](https://redis.io/docs/latest/develop/pubsub/)
