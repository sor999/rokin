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

## 로봇 정지

- Dashboard는 `/app/command`에 `command: "stop"`, `data: {}`를 보낸다. 좌표 입력은 필요하지 않다.
- 선택 필드 `requestId`는 `/topic/cmd-result`에 그대로 반환한다. 화면은 이를 비교해 이전 요청·다른 관제자의 응답이 현재 요청에 연결되지 않도록 한다. Kafka의 `cmd_id`와 별개인 요청 상관관계 식별자이며 중복 실행 방지 키는 아니다.
- API 접수 응답은 정지 완료를 의미하지 않는다. 화면은 해당 `cmd_id`의 로봇 Ack `done`을 받은 뒤 `Stopped`로 표시한다.
- 로봇은 현재 위치를 유지하고 `idle`로 전환한다. 진행 중이던 이동은 `failed`와 `Movement interrupted by stop`으로 끝내고, 정지 명령에는 `done`을 보낸다.
- 정지 확인 제한 시간은 5초다. 초과 시 정지 여부를 확인하지 못했다고 표시하며 `Retry Stop`은 정지만 재요청한다. 연결이 끊기면 전송·재시도 버튼을 비활성화한다.
- 이 기능은 시뮬레이터의 원격 동작 정지다. 연결 단절 중 정지 보장, 영속적인 명령 중복 방지와 재접속 후 결과 복원은 별도 과제다.

| 시나리오 | 자동 검증 | 통합 검증 |
| --- | --- | --- |
| 이동 중 정지 | 좌표 유지, 이동 failed·정지 done | Dashboard → STOMP → Kafka → ROS2 → Ack·DB 확인 |
| 정지 재요청 | 중복 정지 후 좌표 유지 | Retry Stop이 stop을 전송하는지 확인 |
| 이전 응답·연결 단절 | 요청 식별자 반환 테스트 | 이전 응답 무시, 연결 단절 시 버튼 비활성화 |

기존 서비스 경계와 Ack 상태 집합을 유지하므로 새 ADR은 추가하지 않는다.

## 변경 체크리스트

- 생산자와 모든 소비자의 DTO/type/parser를 함께 갱신했는가?
- 중복 메시지 테스트가 있는가?
- 순서가 바뀌거나 이벤트가 늦게 도착해도 최신 상태가 역행하지 않는가?
- 잘못된 payload가 프로세스를 중단시키지 않고 관측 가능한 실패로 남는가?
- 새 Kafka 토픽이 로컬 초기화와 배포 환경 모두에 생성되는가?
- 스키마 변경이 새 Flyway 버전으로 추가되고 Worker SQL과 호환되는가?
