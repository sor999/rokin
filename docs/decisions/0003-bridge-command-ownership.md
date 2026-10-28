# ADR 0003: Fleet 명령 소유권과 로봇 실행 중복 방지

- 상태: 승인
- 날짜: 2026-10-28
- 관련 이슈: https://github.com/sor999/rokin/issues/18

## 맥락

기존 Bridge는 다른 robot_ids를 담당해도 같은 consumer group에 참여했다. Kafka 파티션과
ROS 로봇 담당 범위는 일치하지 않으므로 잘못 받은 명령을 무시하고 커밋하면 유실됐다.
로봇은 동일 cmd_id의 move_to/stop을 다시 적용해 재전송된 과거 명령이 새 동작을 방해할 수 있었다.

## 결정

API 인스턴스는 서버 설정 `fleet.id`(`FLEET_ID`, 기본 `default`)를 명령의 `fleet_id`에 포함한다.
브라우저가 Fleet 소유권을 지정하지 않는다. 명령 토픽은 `cmd.robot`, 키는 기존 robot_id를 유지한다.
Bridge는 ROS 파라미터 `fleet_id`, `robot_ids`로 소유권을 선언한다. Compose는 API와 Bridge에
동일 FLEET_ID를 전달한다. 단독 launch에서도 FLEET_ID를 지정할 수 있다.

Consumer group은 `<prefix>.<fleet_id>.<SHA-256(sorted robot_ids)>`로 결정한다.
다른 Fleet이나 다른 로봇 집합이 같은 group의 파티션을 나눠 갖지 않게 한다.
동일 Fleet의 복제 Bridge는 동일 로봇 집합과 그 로봇 전체에 접근할 수 있는 ROS/DDS 영역을 사용한다.
로봇 ID는 플랫폼에서 유일해야 하며, 한 로봇에는 하나의 실행 주체만 둔다.
소유권 변경은 자동 재배치 기능이 아니며 API 발행 중단과 기존 명령 처리를 마친 뒤 수행한다.

다른 Fleet을 대상으로 하는 유효한 envelope는 해당 Fleet의 별도 group이 처리하므로 건너뛴다.
자기 Fleet 내 소유하지 않은 로봇, 잘못된 cmd_id(UUID), command/data, 좌표 및 JSON은
`dlq.command`로 원본 바이트(Base64), 원본 topic/partition/offset과 원인을 보존한다.
DLQ 발행 확인 또는 ROS publish 성공 후 해당 메시지의 offset만 동기 커밋한다.
ROS/DLQ/커밋 실패 시 같은 메시지를 재시도하고 뒤 메시지의 커밋으로 실패를 덮지 않는다.
같은 레코드를 3회 시도한 뒤에도 실패하면 consumer를 닫고 재연결해 저장된 offset부터 재개한다.
새 group은 할당 시 latest 위치를 먼저 커밋한 뒤 준비 상태로 전환하므로, 첫 명령 실패 후
재연결에서도 latest로 건너뛰지 않는다. 준비 상태는 revoke/종료 시 해제한다.
max.poll.interval 초과나 재할당 시 커밋 실패와 재전송을 허용한다.
DLQ도 커밋 오류 시 중복 발행될 수 있어 원본 topic/partition/offset으로 식별한다.

실제 동작 중복 방지의 소유자는 fake_robot의 순수 `RobotCommands`다.
cmd_id별 명령 fingerprint와 최신 Ack를 TTL 저장소에 보관한다. 동일 명령은 동작 대신 최신 Ack만
반환하고 같은 cmd_id의 다른 내용은 거부한다. 종료/중단된 이동의 최신 Ack도 갱신한다.
진행 중인 이동은 TTL이 지나도 보호한다. 종료 후 TTL은 300초, 상한은 10,000개가 기본이며
ROS 파라미터 `command_dedup_ttl_seconds`, `command_dedup_max_entries`로 조정한다.
만료되지 않은 키는 상한 때문에 축출하지 않고 신규 명령을 failed로 거부한다. TTL 만료 후 재사용은 허용한다.

## 결과와 한계

중복 실행 방지는 로봇 프로세스의 TTL 범위에 한정된다. 재시작·TTL 만료 후까지 보장하는
영속 실행 저널은 별도 과제다. ROS publish 성공은 실제 로봇의 수신/완료를 보장하지 않으며
사용자는 기존 로봇 Ack로 결과를 확인한다. 전송과 실행을 원자적으로 묶는 exactly-once 보장은 하지 않는다.

Consumer의 auto.offset.reset은 기존과 같이 latest다. 새 group 생성 시 과거 명령을 재생하지 않는다.
최초 기동·소유권 변경 시 Bridge 구독 준비 뒤 API 명령 발행을 재개해야 한다.
기존 API/Bridge를 중지하고 dlq.command 토픽을 준비한 뒤 새 API/Bridge를 함께 적용한다.
fleet_id 없는 구 envelope는 새 Bridge의 유효 명령이 아니며 DLQ 대상이다.

## 검증 시나리오

| 시나리오 | 준비 상태 | 자동 검증 | 수동 검증 |
| --- | --- | --- | --- |
| 정상·다중 Fleet | 별도 Fleet과 소유 로봇 | group 분리, 대상 Fleet만 ROS 전달 | 실제 Kafka→ROS→Ack |
| 다른 소유 로봇 집합 | 같은 Fleet, 다른 robot_ids | group digest 분리 | 배치 설정 확인 |
| 재전송 | 같은 cmd_id | 이동/정지 중복, 최신 Ack, TTL·상한 | ROS 재전송 후 위치 확인 |
| 잘못된 payload·소유권 | 원본 바이트 보존 | DLQ 확인 후 정확한 메시지 커밋 | DLQ 조회 |
| ROS·DLQ·커밋 실패 | 주입한 실패 | 원본 재시도, 뒤 offset 커밋 방지 | 재연결 복구 |

기본 검증은 [검증 컨벤션](../conventions/verification.md)을 따른다.
