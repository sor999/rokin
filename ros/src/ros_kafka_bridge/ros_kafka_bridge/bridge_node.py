import datetime
import json
import logging
import uuid

import rclpy
from geometry_msgs.msg import Pose2D
from rclpy.node import Node
from std_msgs.msg import Float32, String

from .consumer import KafkaCommandConsumer
from .commands import FleetCommandRouter, ManagedFleetCommandRouter
from .producer import KafkaTelemetryProducer


class RosKafkaBridgeNode(Node):
    """ROS2 ↔ Kafka 데이터 브릿지 노드.

    - ROS → Kafka (Telemetry): pose / battery / status / ack 토픽을 Kafka로 Produce
    - Kafka → ROS (Command): cmd.robot 토픽을 Consume해 각 로봇의 /fleet/{robot_id}/cmd 로 Relay
    """

    def __init__(self, managed=False, runtime_id=None, **kwargs):
        super().__init__('ros_kafka_bridge_node', **kwargs)

        # 파라미터 선언
        self.declare_parameter('kafka_broker', 'kafka:29092')
        self.declare_parameter('kafka_group_id', 'ros_kafka_bridge')
        self.declare_parameter('fleet_id', 'default')
        self.declare_parameter('command_dlq_topic', 'dlq.command')
        self.declare_parameter('robot_ids', ['robot_1', 'robot_2', 'robot_3'])
        self.declare_parameter('fleet_robot_ids', ['robot_1', 'robot_2', 'robot_3'])
        self.declare_parameter('ownership_generation', '1')

        broker: str = self.get_parameter('kafka_broker').value
        group_id: str = self.get_parameter('kafka_group_id').value
        self._robot_ids: list[str] = self.get_parameter('robot_ids').value

        if managed:
            self._robot_ids = []
            self._router = ManagedFleetCommandRouter(
                self.get_parameter('fleet_id').value, self._on_kafka_command, runtime_id)
        else:
            self._router = FleetCommandRouter(
                self.get_parameter('fleet_id').value, self._robot_ids, self._on_kafka_command,
                fleet_robot_ids=self.get_parameter('fleet_robot_ids').value,
                ownership_generation=self.get_parameter('ownership_generation').value,
            )

        self._producer = KafkaTelemetryProducer(broker)
        self._cmd_publishers = {}
        self._subscriptions_by_robot = {}
        for robot_id in self._robot_ids:
            self.attach_robot(robot_id)

        # Kafka Consumer (cmd.robot → ROS)
        self._consumer = KafkaCommandConsumer(
            broker=broker,
            group_id=self._router.consumer_group(group_id),
            topic='cmd.robot',
            on_message=self._router.dispatch,
            dlq_topic=self.get_parameter('command_dlq_topic').value,
        )
        self._consumer.start()

        self.get_logger().info(
            f"RosKafkaBridgeNode 시작 | broker={broker} | robots={self._robot_ids}"
        )

    def attach_robot(self, robot_id):
        """executor에서 로봇의 명령 발행자와 관측 구독을 추가한다."""
        if robot_id in self._cmd_publishers:
            return
        subscriptions = []
        publisher = self.create_publisher(String, f'/fleet/{robot_id}/cmd', 10)
        try:
            for message_type, topic, callback in (
                    (Pose2D, 'pose', self._on_pose), (Float32, 'battery', self._on_battery),
                    (String, 'status', self._on_status), (String, 'ack', self._on_ack)):
                subscriptions.append(self.create_subscription(
                    message_type, f'/fleet/{robot_id}/{topic}',
                    lambda msg, cb=callback: cb(robot_id, msg), 10))
        except Exception:
            for subscription in subscriptions:
                self.destroy_subscription(subscription)
            self.destroy_publisher(publisher)
            raise
        self._cmd_publishers[robot_id] = publisher
        self._subscriptions_by_robot[robot_id] = subscriptions

    def detach_robot(self, robot_id):
        """명령 라우팅에서 제외한 뒤 ROS 리소스를 해제한다."""
        for subscription in self._subscriptions_by_robot.pop(robot_id, []):
            self.destroy_subscription(subscription)
        publisher = self._cmd_publishers.pop(robot_id, None)
        if publisher is not None:
            self.destroy_publisher(publisher)

    def robot_ready(self, robot_id):
        """Kafka 할당과 로봇 명령·관측 DDS 경로가 모두 준비됐는지 확인한다."""
        publisher = self._cmd_publishers.get(robot_id)
        return (self._consumer.ready.is_set() and publisher is not None
                and publisher.get_subscription_count() > 0
                and all(self.count_publishers(subscription.topic_name) > 0
                        for subscription in self._subscriptions_by_robot[robot_id]))

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
        robot_id = cmd['robot_id']

        msg = String()
        msg.data = json.dumps(cmd)
        self._cmd_publishers[robot_id].publish(msg)
        self.get_logger().info(
            f"[Bridge] Command Relay → /fleet/{robot_id}/cmd | command={cmd.get('command')}"
        )

    # 생명주기

    def destroy_node(self) -> None:
        try:
            self._consumer.stop()
        finally:
            try:
                self._producer.flush()
            finally:
                super().destroy_node()


def main(args=None):
    logging.basicConfig(level=logging.INFO)
    rclpy.init(args=args)
    node = RosKafkaBridgeNode()
    try:
        rclpy.spin(node)
    except KeyboardInterrupt:
        pass
    finally:
        try:
            node.destroy_node()
        finally:
            if rclpy.ok():
                rclpy.shutdown()


if __name__ == '__main__':
    main()
