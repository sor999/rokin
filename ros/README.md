# ROS2 가상 로봇 (fake_robot)

다중 가상 로봇을 시뮬레이션하여 텔레메트리(Pose, Battery, Status)를 발행하고, 제어 명령(move_to, stop)을 수신/처리하는 ROS2 패키지입니다.

## 빠른 시작 (Docker)

### 1. 빌드 및 실행

```bash
# 프로젝트 루트에서 실행
docker compose -f infra/docker-compose.yml up fake_robot --build
```

정상 기동 로그:
```
[fake_robot_node-1] [INFO] FakeRobotNode [robot_1] started at (0.0, 0.0)
[fake_robot_node-2] [INFO] FakeRobotNode [robot_2] started at (5.0, 5.0)
[fake_robot_node-3] [INFO] FakeRobotNode [robot_3] started at (-5.0, 2.0)
```

### 2. 컨테이너 접속

`ros2` CLI는 호스트에서 사용할 수 없고, 반드시 컨테이너 내부에서 실행해야 합니다.

```bash
docker exec -it robot_fake_nodes bash
source /opt/ros/humble/setup.bash
export ROS_LOCALHOST_ONLY=1
```

> DDS daemon 에러(`rclpy.ok()` 관련)가 발생하면 `ros2 daemon stop && ros2 daemon start` 후 재시도하세요.

### 3. 토픽 확인

```bash
# 전체 토픽 목록
ros2 topic list

# Pose 데이터
ros2 topic echo /fleet/robot_1/pose

# 배터리
ros2 topic echo /fleet/robot_1/battery

# 상태 (JSON envelope)
ros2 topic echo /fleet/robot_1/status
```

### 4. 명령 테스트

컨테이너 터미널을 2개 열어서 테스트합니다.

**터미널 1 — ACK 수신 대기:**
```bash
ros2 topic echo /fleet/robot_1/ack
```

**터미널 2 — move_to 명령 전송:**
```bash
ros2 topic pub --once /fleet/robot_1/cmd std_msgs/msg/String \
  '{"data": "{\"cmd_id\":\"test-1\",\"command\":\"move_to\",\"data\":{\"x\":3.0,\"y\":4.0}}"}'
```

**기대 결과 (터미널 1):**
1. `"status": "accepted"` — 명령 수신 즉시
2. `"status": "done"` — 목적지 도착 후 (예: `"Arrived at (3.00, 4.00)"`)

**stop 명령:**
```bash
ros2 topic pub --once /fleet/robot_1/cmd std_msgs/msg/String \
  '{"data": "{\"cmd_id\":\"test-2\",\"command\":\"stop\"}"}'
```

**pose 변화 확인:**

move_to 전송 후 별도 터미널에서 pose를 확인하면 좌표가 변하는 것을 볼 수 있습니다.
```bash
ros2 topic echo /fleet/robot_1/pose
```

### 5. 종료

```bash
docker compose -f infra/docker-compose.yml stop fake_robot
```

## 참고

- 상세 패키지 구조, 파라미터, 토픽 규격은 [fake_robot 패키지 README](src/fake_robot/README.md) 참고
- 로봇 3대(`robot_1`, `robot_2`, `robot_3`)가 각각 다른 속도/위치/주기로 실행됩니다
- `ros2 topic pub` 사용 후 DDS daemon이 불안정해질 수 있습니다. 이 경우 `ros2 daemon stop && ros2 daemon start` 로 재시작하세요
