# ADR 0008: 명령과 Ack 영속 전달

상태: 영속 경로와 격리 장애 검증 구현, 운영 활성화 전 기본 비활성화

## 결정

명령은 API PostgreSQL outbox에 먼저 저장한다. Kafka 전송 확인은 로봇 수신 확인이 아니다.
현재 온라인과 runtime 검증을 유지하며, 멱등성 키 재조회는 오프라인에서도 기존 명령을 반환한다.
requestId는 응답 연결용, idempotencyKey는 Fleet 내 요청 중복 방지용이다.

발행 작업은 SKIP LOCKED와 1초 임대 및 토큰을 사용한다. API당 최대 10개 병렬 발행으로 앞선 전송 대기가 정지를 막지 않게 한다. 임대가 만료되면 같은 cmd_id를 재전송한다.
로봇별 순번은 DB에서 발급하며, 실행 시작 제한은 이동 10초와 정지 5초다.
미발행 만료는 EXPIRED, 발행 시도 후 수신이 불명확하면 OUTCOME_UNKNOWN이다.
실제 로봇 결과 없이 failed Ack를 만들지 않는다.

Worker는 새 Ack event_id 수신 확인을 상태 전이와 같은 트랜잭션에 기록한다.
중복 또는 역행 Ack에도 수신 확인을 남기며 충돌하는 이벤트 식별자는 롤백한다.
명령 소유 Fleet과 로봇이 일치하는 이벤트만 영속 경로의 수신 확인 대상이다.
기존 event_id 없는 Ack 경로와 이력은 유지한다.

## ROS 연결

ROS의 SQLite WAL/FULL 영속 볼륨에 수신함, 실행 기록과 Ack를 저장한다.
로봇 기록 전에는 동작하지 않고 최종 DB 확인 전에는 Ack 전송 사본을 지우지 않는다.
재시작한 실행 중 명령은 재개하지 않고 결과 불확실 상태로 회수한다.
명령과 Ack 이력은 자동 삭제하지 않는다. 전체 저장 사본의 동시 유실은 보장 범위 밖이다.

## 배포

COMMAND_DURABLE_ENABLED 기본 false. API, Worker, ROS와 화면의 연결 검증 후 함께 활성화한다.
전환 시 명령 접수를 막고 진행 명령을 정리한다. API/Flyway를 먼저, Worker를 다음에 배치한다.
기존 마이그레이션을 수정하거나 Flyway repair를 하지 않는다. 기존 명령의 보존은 소급 보장하지 않는다.
README는 변경하지 않는다.

## Kafka 복제

별도 검증 클러스터는 KRaft broker/controller 3개와 각 영속 볼륨을 사용한다.
명령과 Ack 및 DLQ는 RF=3, min.insync.replicas=2, unclean.leader.election.enable=false다.
모든 관련 producer는 acks=all과 멱등 전송을 명시한다. 복제본 부족 시 가용성보다 보존을 우선한다.
토픽 초기화는 기존 RF와 설정이 다르면 중단한다. 기존 토픽을 자동 삭제하거나 재생성하지 않는다.

이 클러스터는 단일 개발 호스트의 검증용이다. 운영에서는 브로커 및 메타데이터 quorum을
서로 다른 서버와 장애 영역에 배치한다. PostgreSQL 및 ROS 볼륨 전체의 동시 유실,
호스트 장애 후 실행 서버 인계는 이번 보장 범위에 포함하지 않는다.
