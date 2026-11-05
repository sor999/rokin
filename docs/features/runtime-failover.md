# 단일 활성 ROS 런타임 장애 인계

5-A에서는 같은 Fleet에 활성 관리 런타임 하나와 대기 런타임을 배치한다.
로봇별 서버 분배와 다중 활성 실행은 5-B에서 진행한다.
[ADR 0010](../decisions/0010-runtime-lease-failover.md)에 소유권과 복구 계약을 기록한다.

## 실행 권한

PostgreSQL의 Fleet 잠금으로 lease 획득과 갱신을 직렬화한다. 기본 TTL은 12초이고
관리 노드는 2초마다 갱신한다. 새 UUID가 얻은 session epoch를 fencing token으로 쓴다.
만료된 UUID는 재사용하지 않는다. 종료된 관리 프로세스는 새 UUID로 재시작한다.

로컬 기한은 HTTP 요청 시작의 CLOCK_BOOTTIME에 TTL을 더하고 2초를 뺀 값이다.
Linux가 필요하며 늦은 응답이나 정지 후 재개로 기한을 되살릴 수 없다. HTTP 제한은
3초다. API는 서버 lease 잔여 기간이 5초 이하면 새 명령을 거부한다. DB 시계는
점진적으로 보정해야 하며 강제 시계 점프와 안전 여유를 넘는 시계 속도 차이는 보장 밖이다.

대기 서버는 ROS 로봇과 명령 Consumer를 생성하지 않는다. 활성 서버의 명령 group은
`<prefix>.<fleet>.managed.lease.<epoch>`이며 DDS 토픽은
`/fleet/runtime_<UUID의 하이픈을 밑줄로 변경>/<robot>/<topic>`이다. 다른 세대의
Consumer와 DDS가 새 실행에 명령을 전달하지 못하게 한다. API도 만료된 보고와 관측을 거부한다.

기한이 만료되면 명령, 이동 갱신과 관측 발행을 중단하고 프로세스를 종료한다. 기본 Compose의
재시작 정책은 새 UUID로 대기하게 한다. 새 활성 서버는 DB에 저장된 시작 위치와 초기 배터리로
복구하며 이전 이동을 재개하지 않는다. 준비 보고와 온라인 확인 전에는 명령을 접수하지 않는다.

## 영속 저널

각 서버는 자신의 SQLite 영속 볼륨을 사용한다. 같은 파일이나 볼륨을 런타임끼리 공유하지 않는다.
`COMMAND_DURABLE_ENABLED`의 기본값은 계속 false다. 이를 켠 경우 대기 상태에서도 자신의
과거 Ack와 수신 확인을 회수한다. 완료 결과가 없는 이전 실행 의도와 진행 중 명령은
`OUTCOME_UNKNOWN`으로 추적한다. 완료 결과가 있으면 이를 보존하며 실패로 바꾸지 않는다.
해당 서버의 디스크에 접근할 수 없다면 결과 회수는 디스크 복구까지 지연될 수 있다.
다른 서버의 볼륨을 임의로 지우거나 복사해 덮어쓰지 않는다.

## 전환 절차

1. 명령 접수를 중단하고 진행 중 명령을 정리한다.
2. 기존 관리 런타임을 모두 정상 종료한다. 이전 바이너리와 새 런타임을 동시에 실행하지 않는다.
3. API/Flyway로 새 V14를 적용한다. 기존 마이그레이션과 볼륨은 유지한다.
4. API와 관리 ROS에 `SIMULATOR_LEASE_ENABLED=true`를 함께 적용한다.
5. API를 배치한 뒤 각각 독립적인 볼륨을 가진 활성 후보와 대기 후보를 시작한다.
6. 활성 하나, 준비 및 온라인 상태와 명령 Ack를 확인한 뒤 접수를 재개한다.

API와 관리 ROS의 미설정 기본값은 호환용 false이며 기본 Compose에서는 true다.
`SIMULATOR_LEASE_MILLIS`는 API의 6000~30000ms 설정이고 기본 12000ms다. ROS는
응답 TTL을 사용한다. Fleet에서 lease를 사용한 후에는 레거시 등록 API로 소유권을
덮어쓸 수 없다. 롤백을 위해 이력을 삭제하거나 오래된 바이너리를 그대로 실행하지 않는다.

실제 서버에서는 각 서버의 ROS 컨테이너와 전용 영속 볼륨, 접근 가능한 내부 API 및 Kafka를
별도로 배치한다. 아래 단일 PC 검증은 서버 장애 영역과 물리 로봇의 정지를 보장하지 않는다.

## 격리 검증

```bash
COMMAND_DURABLE_ENABLED=true scripts/failover/lab.sh up
python3 scripts/failover/verify.py --output /tmp/runtime-failover.json
scripts/failover/lab.sh stop
```

프로젝트는 `rokin-runtime-lab`, API는 18491/18492, LB는 18490, DDS 도메인은 89다.
개발 환경과 볼륨을 분리하고 종료 시 볼륨을 보존한다. 실행 도구는 전용 DB 테스트 계정과
임시 내부 토큰을 사용한다. up은 서비스를 빌드하고 토큰을 함께 갱신한다. 실행 잠금으로
up/stop과 장애 주입의 동시 실행을 거부한다.

검증은 양쪽 API의 준비 및 온라인 일치, 이동 Ack와 위치 변화 후 다음 장애를 주입한다.
API 단절은 해당 ROS의 내부 API 프록시만 중단하며 Kafka와 DDS는 계속 연결한다.
정지는 `docker pause`로 컨테이너 전체를 동결한다. ROS 실행 도구의 부모 PID만 SIGSTOP하면
자식 관리 프로세스가 계속 실행될 수 있으므로 정지 시험으로 인정하지 않는다.

| 실제 주입 | 인계 및 준비 확인 | 검증 결과 |
| --- | --- | --- |
| 활성 서버 SIGKILL | 17.731초 | 대기 서버 인계, 과거 이동 미재개 |
| 활성 서버의 관리 API 단절 | 19.027초 | 이전 실행 종료, 새 epoch 인계 |
| 컨테이너 전체 정지 후 재개 | 19.599초 | 이전 UUID 재활성화 차단 |

위 수치는 2026-11-05 단일 개발 PC에서 각 한 번 측정한 관측값이며 운영 지연 보장이 아니다.
모든 경우 초기 위치와 배터리, 재시작한 서버의 비선점, 과거 명령 Kafka 재전송 차단,
이전 명령의 결과 확인 불가 복구 및 새 정지 명령의 최종 DB 저장을 확인했다.
API 한 대 재시작도 lease 소유자를 바꾸지 않았다. 자동 도구는 SockJS/STOMP와 SSE를
직접 연결한다. 화면 변경은 없으며 이 작업에서 별도 브라우저 시각 검증은 수행하지 않았다.

API 테스트 114개 중 111개와 bootJar가 통과했고 실제 PostgreSQL의 lease 경쟁 및 V14를 검증했다.
별도 MIGRATION_TEST_URL을 요구하는 기존 AckMigrationTest 3개는 생략했다.
ROS 테스트 79개가 모두 통과했으며 실제 Kafka와 ROS2의 동적 추가 및 삭제와 이동 및 정지
시험을 포함한다.
Worker의 PostgreSQL 테스트 및 vet와 빌드, Dashboard 테스트와 typecheck 및 build도 통과했다.
장시간 자원 부하, 로봇 분배, 여러 물리 서버 및 실제 하드웨어 장애는 이 검증에 포함하지 않는다.
