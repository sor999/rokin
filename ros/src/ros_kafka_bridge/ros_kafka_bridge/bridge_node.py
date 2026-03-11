import datetime
import json
import uuid

import rclpy
from geometry_msgs.msg import Pose2D
from rclpy.node import Node
from std_msgs.msg import Float32, String

from .consumer import KafkaCommandConsumer
from .producer import KafkaTelemetryProducer


class RosKafkaBridgeNode(Node):
    """ROS2 ↔ Kafka 데이터 브릿지 노드.

    - ROS → Kafka (Telemetry): pose / battery / status / ack 토픽을 Kafka로 Produce
    - Kafka → ROS (Command): cmd.robot 토픽을 Consume해 각 로봇의 /fleet/{robot_id}/cmd 로 Relay
    """

    def __init__(self):
        super().__init__('ros_kafka_bridge_node')

        # 파라미터 선언
        self.declare_parameter('kafka_broker', 'kafka:29092')
        self.declare_parameter('kafka_group_id', 'ros_kafka_bridge')
        self.declare_parameter('robot_ids', ['robot_1', 'robot_2', 'robot_3'])

        broker: str = self.get_parameter('kafka_broker').value
        group_id: str = self.get_parameter('kafka_group_id').value
        self._robot_ids: list[str] = self.get_parameter('robot_ids').value

        # Kafka Producer (ROS → Kafka)
        self._producer = KafkaTelemetryProducer(broker)

        # 각 로봇의 Command Publisher (Kafka → ROS)
        self._cmd_publishers: dict[str, object] = {}
        for robot_id in self._robot_ids:
            self._cmd_publishers[robot_id] = self.create_publisher(
                String, f'/fleet/{robot_id}/cmd', 10
            )

        # ROS 구독 (Telemetry → Kafka)
        for robot_id in self._robot_ids:
            self.create_subscription(
                Pose2D,
                f'/fleet/{robot_id}/pose',
                lambda msg, rid=robot_id: self._on_pose(rid, msg),
                10,
            )
            self.create_subscription(
                Float32,
                f'/fleet/{robot_id}/battery',
                lambda msg, rid=robot_id: self._on_battery(rid, msg),
                10,
            )
            self.create_subscription(
                String,
                f'/fleet/{robot_id}/status',
                lambda msg, rid=robot_id: self._on_status(rid, msg),
                10,
            )
            self.create_subscription(
                String,
                f'/fleet/{robot_id}/ack',
                lambda msg, rid=robot_id: self._on_ack(rid, msg),
                10,
            )

        # Kafka Consumer (cmd.robot → ROS)
        self._consumer = KafkaCommandConsumer(
            broker=broker,
            group_id=group_id,
            topic='cmd.robot',
            on_message=self._on_kafka_command,
        )
        self._consumer.start()

        self.get_logger().info(
            f"RosKafkaBridgeNode 시작 | broker={broker} | robots={self._robot_ids}"
        )

    # 헬퍼

    def _make_envelope(self, robot_id: str, msg_type: str, data: dict) -> dict:
        """공통 Telemetry Envelope를 생성한다."""
        return {
            'event_id': str(uuid.uuid4()),
            'timestamp': datetime.datetime.now(datetime.timezone.utc).isoformat(),
            'robot_id': robot_id,
            'type': msg_type,
            'data': data,
        }

    # ROS → Kafka 콜백

    def _on_pose(self, robot_id: str, msg: Pose2D) -> None:
        envelope = self._make_envelope(robot_id, 'pose', {'x': msg.x, 'y': msg.y})
        self._producer.produce('telemetry.pose', key=robot_id, value=envelope)

    def _on_battery(self, robot_id: str, msg: Float32) -> None:
        envelope = self._make_envelope(robot_id, 'battery', {'level': msg.data})
        self._producer.produce('telemetry.battery', key=robot_id, value=envelope)

    def _on_status(self, robot_id: str, msg: String) -> None:
        """status는 fake_robot이 이미 Envelope 형태의 JSON으로 발행하므로 그대로 전달한다."""
        try:
            payload = json.loads(msg.data)
        except json.JSONDecodeError:
            self.get_logger().warning(f"[Bridge] status JSON 파싱 실패 (robot={robot_id})")
            return
        self._producer.produce('telemetry.status', key=robot_id, value=payload)

    def _on_ack(self, robot_id: str, msg: String) -> None:
        """ack는 fake_robot이 JSON으로 발행하므로 그대로 ack.robot 토픽에 전달한다."""
        try:
            payload = json.loads(msg.data)
        except json.JSONDecodeError:
            self.get_logger().warning(f"[Bridge] ack JSON 파싱 실패 (robot={robot_id})")
            return
        self._producer.produce('ack.robot', key=robot_id, value=payload)

    # Kafka → ROS 콜백

    def _on_kafka_command(self, cmd: dict) -> None:
        """Kafka cmd.robot에서 수신한 Command를 해당 로봇의 ROS 토픽으로 Relay한다."""
        robot_id = cmd.get('robot_id')
        if not robot_id:
            self.get_logger().warning("[Bridge] robot_id 없는 command 수신, 무시합니다.")
            return
        if robot_id not in self._cmd_publishers:
            self.get_logger().warning(f"[Bridge] 알 수 없는 robot_id: {robot_id}, 무시합니다.")
            return

        msg = String()
        msg.data = json.dumps(cmd)
        self._cmd_publishers[robot_id].publish(msg)
        self.get_logger().info(
            f"[Bridge] Command Relay → /fleet/{robot_id}/cmd | command={cmd.get('command')}"
        )

    # 생명주기

    def destroy_node(self) -> None:
        self._consumer.stop()
        self._producer.flush()
        super().destroy_node()


def main(args=None):
    rclpy.init(args=args)
    node = RosKafkaBridgeNode()
    try:
        rclpy.spin(node)
    except KeyboardInterrupt:
        pass
    finally:
        node.destroy_node()
        if rclpy.ok():
            rclpy.shutdown()


if __name__ == '__main__':
    main()
