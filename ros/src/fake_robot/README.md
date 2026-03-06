# `fake_robot` ROS2 Package

이 패키지는 다수의 가상 로봇 객체를 시뮬레이션하고 텔레메트리(`pose`, `battery`, `status`)를 Publish하며, 제어 명령(`cmd`)에 따라 이동 및 상태 갱신을 수행하는 ROS2(`ament_python`) 노드입니다.

## 1. 전제 조건 (Prerequisites)

- **ROS2** 환경 (예: Humble, Iron 등) 설치 완료
- **colcon** 빌드 시스템 설치 완료 (`sudo apt install python3-colcon-common-extensions` 등)
- Python 3.10 이상

## 2. Docker를 이용한 실행 (권장)

macOS나 ROS2가 설치되지 않은 환경에서는 Docker 환경을 이용하는 것이 가장 편리합니다.
인프라(Kafka 등)와 함께 로봇 노드들을 띄울 수 있습니다.

```bash
# 프로젝트 루트 디렉토리에서 실행
docker-compose -f infra/docker-compose.yml up -d fake_robot
```

종료하려면 아래 명령어를 사용합니다.
```bash
docker-compose -f infra/docker-compose.yml stop fake_robot
```

### VS Code Dev Container 활용
제공된 `.devcontainer` 구성을 사용하면 VS Code에서 `rclpy` 자동완성 및 문법 검사를 완벽히 지원받으며 `ros` 디렉토리를 개발 환경으로 띄울 수 있습니다.
- VS Code 명령 팔레트(`Cmd+Shift+P`) -> `Dev Containers: Reopen in Container` 선택

---

## 3. 로컬 빌드 방법 (ROS2가 설치된 환경)

모노레포의 `ros` 디렉토리 위에서 빌드합니다.

```bash
cd ros/
colcon build --symlink-install --packages-select fake_robot
```

> **참고**: `symlink-install` 옵션을 지정하면 Python 스크립트 수정 시 재빌드 없이 변경사항이 실시간으로 반영됩니다.

## 4. 로컬 실행 방법 (단일 로봇)

빌드가 완료된 작업 공간(Workspace)을 소싱(sourcing)한 후 단일 노드를 실행할 수 있습니다.

```bash
# 환경 설정 로드
source install/setup.bash  # bash의 경우
# 또는 source install/setup.zsh # zsh의 경우

# 기본 파라미터로 노드 실행
ros2 run fake_robot fake_robot_node
```

**커스텀 파라미터로 실행**하실 때는 파라미터 오버라이딩(`-p`) 옵션을 사용합니다.

```bash
ros2 run fake_robot fake_robot_node --ros-args \
  -p robot_id:=my_robot \
  -p speed:=2.5 \
  -p start_x:=10.0 \
  -p start_y:=10.0
```

## 5. 로컬 실행 방법 (다중 로봇 런치 스크립트)

여러 대의 로봇(기본 3대: `robot_1`, `robot_2`, `robot_3`)을 동시에 시뮬레이션 하려면 제공된 `demo.launch.py` 파일을 이용하세요.

```bash
source install/setup.bash
ros2 launch fake_robot demo.launch.py
```

## 6. 생성 및 구독하는 토픽 (Topics)

이 로봇 노드는 `/fleet/{robot_id}` 네임스페이스 경로 하위에 토픽을 구성합니다. (예: `robot_id` 가 `robot_1` 라면 `/fleet/robot_1/pose` 등)

*   `Publishers`
    *   `/fleet/{robot_id}/pose` (`geometry_msgs/Pose2D`): 현재 (X, Y) 좌표
    *   `/fleet/{robot_id}/battery` (`std_msgs/Float32`): 잔여 배터리 (0~100)
    *   `/fleet/{robot_id}/status` (`std_msgs/String`): 현재 상태 (`idle`, `moving`, `arrived` 등) JSON 포맷
    *   `/fleet/{robot_id}/ack` (`std_msgs/String`): 명령 수신 및 처리 결과 알림 JSON 포맷
*   `Subscribers`
    *   `/fleet/{robot_id}/cmd` (`std_msgs/String`): 로봇 제어 명령

### 제어 명령 페이로드 예시 (`cmd`)

로봇 이동(`move_to`):
```json
{
  "cmd_id": "1234-abcd",
  "command": "move_to",
  "data": {
    "x": 20.0,
    "y": 15.0
  }
}
```

정지(`stop`):
```json
{
  "cmd_id": "5678-efgh",
  "command": "stop"
}
```

## 7. Docker 환경에서 동작 테스트

### 서비스 빌드 및 실행

```bash
# 프로젝트 루트에서 실행
docker compose -f infra/docker-compose.yml up fake_robot --build
```

정상 기동 시 아래와 같은 로그가 출력됩니다.

```
[fake_robot_node-1] [INFO] FakeRobotNode [robot_1] started at (0.0, 0.0)
[fake_robot_node-2] [INFO] FakeRobotNode [robot_2] started at (5.0, 5.0)
[fake_robot_node-3] [INFO] FakeRobotNode [robot_3] started at (-5.0, 2.0)
```

### 컨테이너 접속

`ros2` CLI는 컨테이너 내부에서만 사용할 수 있습니다.

```bash
docker exec -it robot_fake_nodes bash
source /opt/ros/humble/setup.bash
```

### 토픽 발행 확인

```bash
# 전체 토픽 목록
ros2 topic list

# Pose 데이터 수신
ros2 topic echo /fleet/robot_1/pose

# 배터리 수신
ros2 topic echo /fleet/robot_1/battery

# 상태(JSON envelope) 수신
ros2 topic echo /fleet/robot_1/status
```

### move_to 명령 및 ACK 흐름 테스트

터미널 2개를 열어 하나는 ACK를 수신하고, 다른 하나에서 명령을 전송합니다.

**터미널 1 — ACK 수신 대기:**
```bash
ros2 topic echo /fleet/robot_1/ack
```

**터미널 2 — move_to 명령 전송:**
```bash
ros2 topic pub --once /fleet/robot_1/cmd std_msgs/msg/String \
  '{"data": "{\"cmd_id\":\"test-1\",\"command\":\"move_to\",\"data\":{\"x\":1.0,\"y\":1.0}}"}'
```

**기대 결과 (터미널 1):**
1. `"status": "accepted"` — 명령 수신 즉시 발행
2. `"status": "done"` — 목적지 도착 후 발행 (예: `"Arrived at (1.00, 1.00)"`)

### stop 명령 테스트

```bash
ros2 topic pub --once /fleet/robot_1/cmd std_msgs/msg/String \
  '{"data": "{\"cmd_id\":\"test-2\",\"command\":\"stop\"}"}'
```

**기대 결과:** `"status": "done"`, `"message": "Robot stopped"` ACK 즉시 발행
