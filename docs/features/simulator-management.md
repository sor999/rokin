# 가상 로봇 관리

## 사용자·메시지 흐름

Fleet의 추가 폼 → Spring 등록 API → PostgreSQL 실행 의도 저장 → ROS 관리 노드의
2초 주기 조회 → 로봇·Bridge 연결 → 실행 보고 → 기존 SSE 지도·STOMP 명령 사용.
삭제는 의도를 먼저 변경하고 ROS 종료 확인까지 추적한다. 텔레메트리 이력은 삭제하지 않는다.

| 시나리오 | 준비 상태 | 자동 검증 | 수동 검증 |
| --- | --- | --- | --- |
| 추가·이동·정지·삭제 | 격리 DB/Kafka/API/ROS/Dashboard | API·ROS 상태 및 Ack | Fleet 폼, 지도, 상세 명령, 삭제 확인 |
| 중복 등록·목록 반복 | 같은 ID·같은 snapshot | 409, 중복 ROS 생성 없음 | 오류 메시지, 기존 이동 유지 |
| 잘못된 입력·상한 | 음수 속도·잘못된 ID·실행 상한 | 400/409, 저장 없음 | 폼 오류 표시 |
| 삭제 후 지연 보고 | 이전 설정 버전·SSE | 되살아나지 않음 | 카드·사이드바에서 제거 |
| 재시작·통신 장애 | 기존 등록·이동 중인 로봇 | 저장 목록 복구, 과거 runtime 명령 거부 | 시작 위치·배터리 초기화, 연결 안내 |

## 실행 설정

API와 ROS 관리 런타임에 동일한 `SIMULATOR_INTERNAL_TOKEN`을 비공개 환경 변수로 설정한다.
예시 문자열을 실제 값으로 사용하지 않는다. 로컬 실행 전 `export SIMULATOR_INTERNAL_TOKEN="$(openssl rand -hex 32)"`로 설정할 수 있다. 토큰이 없으면 내부 API는 503을 반환하고 관리
런타임은 시작하지 않는다. 토큰은 브라우저·로그·공개 API 응답에 전달하지 않는다.

- `SIMULATOR_API_URL`: ROS에서 접근하는 Spring 주소. Compose 기본 `http://api:8080`.
- `SIMULATOR_MAX_ROBOTS`: API의 동시 실행 상한. 기본 20, 기본 로봇을 위해 최솟값 3.
- `FLEET_ID`: API와 ROS가 동일한 값 사용. Fleet당 관리 런타임 하나만 배치.
- `KAFKA_BROKER_URL`: ROS에서 접근하는 브로커. Compose 기본 `kafka:29092`.

기존 정적 데모/Bridge를 먼저 종료한 뒤 API의 Flyway V6를 적용하고 관리 런타임을 시작한다.
기본 robot_1~3은 API가 최초에 저장하며 삭제한 ID를 복구하지 않는다. 관리 런타임은
`ros2 run ros_kafka_bridge simulation_manager`로 실행한다. 정적 launch는 별도 용도로 유지한다.
같은 Fleet에서 두 방식을 동시에 실행하지 않는다.

## 공개 API

- `POST /api/simulations`: `{robotId, startX, startY, speed}` → 202와 저장된 설정.
- `GET /api/simulations`: 삭제한 ID를 포함하는 전체 등록 목록. 삭제 이력은 ID 재사용 방지와 UI tombstone으로 유지.
- `DELETE /api/simulations/{robotId}`: 202와 삭제 의도. 반복 삭제는 같은 버전 반환.
- 중복 ID·실행 상한 409, 입력 오류 400, 없는 ID 404.

`desiredState`는 RUNNING/DELETED, `state`는 STARTING/RUNNING/FAILED/DELETING/DELETED다.
명령 연결이 30초 안에 준비되지 않으면 FAILED로 표시하고 자동 재확인한다.
RUNNING 보고가 10초 동안 없으면 공개 상태는 STARTING으로 표시하고 명령을 거부한다.
실행 상한은 실제 삭제 완료 전까지 슬롯을 유지한다. 삭제 실패도 이 슬롯을 유지한다.

재시작 복구는 시작 좌표·속도 설정의 복구이며 실행 중 위치·배터리·이동 명령의 복구가 아니다.

시작 좌표는 각 축 -1,000,000~1,000,000m 범위로 제한한다. 프런트엔드는 요청 전에
0 초과 10 이하의 속도를 검증하고 등록 조회는 5초 타임아웃 후 자동 재시도한다.
예시 토큰은 빈 값이므로 복사만으로 관리 인증이 활성화되지 않는다.

배포 순서는 API/Flyway V6 → Worker → 관리 런타임이다. V5 이전 Worker로는 현재
실행 인스턴스가 DB에 보존되지 않으므로 함께 갱신한다. 내부 런타임 등록은 서버 전용
토큰을 사용하며 종료된 UUID는 재사용하지 않는다.

텔레메트리 인덱스는 V6에서 CONCURRENTLY로 생성하며 같은 이름의 .sql.conf에서
executeInTransaction=false를 지정한다. 신규 V5에는 런타임 메타데이터와 컬럼 추가만 둔다.
현재 세션과 일치하는 이벤트만 수신하고 온라인 판정에는 서버 수신 시각을 사용한다.
따라서 이전 인스턴스의 미래 시각 이벤트가 현재 로봇의 온라인 복구를 막지 않는다.

Flyway의 PostgreSQL advisory lock은 spring.flyway.postgresql.transactional-lock=false로
세션 잠금을 사용한다. 동시 인덱스 생성이 Flyway 자체 트랜잭션을 기다리는 상황을 방지한다.
설정 근거: https://documentation.red-gate.com/flyway/reference/configuration/flyway-namespace/flyway-postgresql-namespace/flyway-postgresql-transactional-lock-setting
