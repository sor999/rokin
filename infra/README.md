# Infrastructure Execution Guide

이 저장소의 인프라(PostgreSQL, Kafka)를 실행하는 방법입니다.

## 1. 환경 설정

먼저 루트 디렉토리에 있는 `.env.example` 파일을 복사하여 `.env` 파일을 생성합니다.

```bash
cp .env.example .env
```

필요에 따라 `.env` 파일의 환경 변수를 수정할 수 있습니다.

## 2. Docker Compose 실행

Docker Compose를 사용하여 모든 서비스를 백그라운드에서 실행합니다.

```bash
# infra 디렉토리에서 실행하거나 루트에서 경로를 지정하여 실행
docker-compose -f infra/docker-compose.yml up -d
```

실행 중인 컨테이너 상태는 다음 명령어로 확인할 수 있습니다.

```bash
docker ps
```

## 3. Kafka 토픽 초기화

Kafka 컨테이너가 정상적으로 부팅된 후(약 10~15초 소요), 초기 토픽들을 생성합니다.

```bash
# 실행 권한 부여 (최초 1회)
chmod +x infra/kafka/topic-init.sh

# 스크립트 실행
./infra/kafka/topic-init.sh
```

이 스크립트는 다음 토픽들을 생성합니다:
- `telemetry.pose`
- `telemetry.battery`
- `telemetry.status`
- `cmd.robot`
- `ack.robot`

## 4. ROS2 가상 로봇(fake_robot) 실행

fake_robot 서비스를 빌드하고 실행합니다.

```bash
docker compose -f infra/docker-compose.yml up fake_robot --build
```

3개의 로봇 노드(`robot_1`, `robot_2`, `robot_3`)가 컨테이너 내부에서 실행됩니다.

### 토픽 확인 및 테스트

로봇 노드는 Docker 컨테이너 안에서 실행되므로, `ros2` 명령어도 컨테이너 안에서 실행해야 합니다.

```bash
# 컨테이너 쉘 접속
docker exec -it robot_fake_nodes bash
source /opt/ros/humble/setup.bash

# 토픽 목록 확인
ros2 topic list

# Pose 데이터 수신
ros2 topic echo /fleet/robot_1/pose

# 배터리 데이터 수신
ros2 topic echo /fleet/robot_1/battery

# 상태 데이터 수신
ros2 topic echo /fleet/robot_1/status

# move_to 명령 전송
ros2 topic pub --once /fleet/robot_1/cmd std_msgs/msg/String \
  '{"data": "{\"cmd_id\":\"test-1\",\"command\":\"move_to\",\"data\":{\"x\":3.0,\"y\":4.0}}"}'

# ACK 수신 확인 (accepted → done)
ros2 topic echo /fleet/robot_1/ack
```

## 5. 서비스 정지

인프라를 정지하려면 다음 명령어를 실행합니다.

```bash
docker-compose -f infra/docker-compose.yml down
```
