# Fleet Telemetry Domain

## 용어

- **Fleet**: 관제 대상 전체 로봇 군집입니다. ROS 토픽은 `/fleet/{robot_id}/...` 형태를 사용합니다.
- **Telemetry**: 로봇이 주기적으로 발행하는 pose, battery, status 관측 데이터입니다.
- **Command**: 사용자가 로봇에 보내는 `move_to`, `stop` 같은 제어 지시입니다.
- **Ack**: Command의 수신, 수행, 완료 또는 실패 상태를 알리는 응답입니다.
- **Idempotency**: 동일 메시지를 여러 번 받아도 한 번 처리한 것과 같은 상태를 유지하는 성질입니다.

## 메시지 불변식

| 메시지 | 식별자 | 중복 처리 기준 | 현재 Kafka 토픽 |
| --- | --- | --- | --- |
| Pose | `event_id` | DB unique/`ON CONFLICT DO NOTHING` | `telemetry.pose` |
| Battery | `event_id` | DB unique/`ON CONFLICT DO NOTHING` | `telemetry.battery` |
| Status | `event_id` | DB unique/`ON CONFLICT DO NOTHING` | `telemetry.status` |
| Command | `cmd_id` | 로봇 동작을 같은 명령에 한 번만 적용 | `cmd.robot` |
| Ack | `cmd_id` + 상태 전이 | 같은 전이를 중복 표시/적재하지 않음 | `ack.robot` |
| 처리 실패 | 원본 메시지 식별자 | 원본과 실패 원인을 보존 | `dlq.telemetry` |

모든 이벤트 시간은 타임존을 포함한 ISO-8601 문자열 또는 PostgreSQL `TIMESTAMPTZ`로 취급합니다.
`robot_id`, 식별자, `timestamp`, 메시지 종류는 경계를 넘을 때 임의로 재명명하지 않습니다.

## 변경 체크리스트

- 생산자와 모든 소비자의 DTO/type/parser를 함께 갱신했는가?
- 중복 메시지 테스트가 있는가?
- 순서가 바뀌거나 이벤트가 늦게 도착해도 최신 상태가 역행하지 않는가?
- 잘못된 payload가 프로세스를 중단시키지 않고 관측 가능한 실패로 남는가?
- 새 Kafka 토픽이 로컬 초기화와 배포 환경 모두에 생성되는가?
- 스키마 변경이 새 Flyway 버전으로 추가되고 Worker SQL과 호환되는가?
