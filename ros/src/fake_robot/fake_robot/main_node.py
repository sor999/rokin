import rclpy
from rclpy.node import Node
from geometry_msgs.msg import Pose2D
from std_msgs.msg import Float32, String
import json
import uuid
import datetime

from .logic import FakeRobotLogic

class FakeRobotNode(Node):
    def __init__(self):
        super().__init__('fake_robot_node')

        # Declare parameters
        self.declare_parameter('robot_id', 'robot_1')
        self.declare_parameter('publish_rate_hz', 2.0)
        self.declare_parameter('speed', 1.0)
        self.declare_parameter('start_x', 0.0)
        self.declare_parameter('start_y', 0.0)
        self.declare_parameter('battery_drain_rate', 0.1)

        # Get parameters
        self.robot_id = self.get_parameter('robot_id').value
        publish_rate_hz = self.get_parameter('publish_rate_hz').value
        speed = self.get_parameter('speed').value
        start_x = self.get_parameter('start_x').value
        start_y = self.get_parameter('start_y').value
        battery_drain_rate = self.get_parameter('battery_drain_rate').value

        # Initialize Logic
        self.logic = FakeRobotLogic(
            x=start_x, y=start_y, speed=speed, battery=100.0, battery_drain_rate=battery_drain_rate
        )

        # Publishers
        self.pose_pub = self.create_publisher(Pose2D, f'/fleet/{self.robot_id}/pose', 10)
        self.battery_pub = self.create_publisher(Float32, f'/fleet/{self.robot_id}/battery', 10)
        self.status_pub = self.create_publisher(String, f'/fleet/{self.robot_id}/status', 10)
        self.ack_pub = self.create_publisher(String, f'/fleet/{self.robot_id}/ack', 10)

        # Subscriber
        self.cmd_sub = self.create_subscription(
            String, f'/fleet/{self.robot_id}/cmd', self.cmd_callback, 10
        )

        # Timer
        self.dt = 1.0 / publish_rate_hz
        self.timer = self.create_timer(self.dt, self.timer_callback)

        # 현재 진행 중인 move_to 명령의 cmd_id (완료 ACK 발행용)
        self._pending_cmd_id = None

        self.get_logger().info(f"FakeRobotNode [{self.robot_id}] started at ({start_x}, {start_y})")

    def _generate_envelope(self, msg_type, data):
        return {
            "event_id": str(uuid.uuid4()),
            "timestamp": datetime.datetime.now(datetime.timezone.utc).isoformat(),
            "robot_id": self.robot_id,
            "type": msg_type,
            "data": data
        }

    def _publish_ack(self, cmd_id, status, message, data=None):
        ack_data = {
            "cmd_id": cmd_id,
            "timestamp": datetime.datetime.now(datetime.timezone.utc).isoformat(),
            "robot_id": self.robot_id,
            "status": status,
            "message": message,
            "data": data or {}
        }
        msg = String()
        msg.data = json.dumps(ack_data)
        self.ack_pub.publish(msg)

    def cmd_callback(self, msg):
        try:
            cmd_payload = json.loads(msg.data)
            cmd_id = cmd_payload.get('cmd_id', str(uuid.uuid4()))
            command = cmd_payload.get('command')
            data = cmd_payload.get('data', {})

            self.get_logger().info(f"Received command: {command}")

            if command == 'move_to':
                target_x = data.get('x', self.logic.x)
                target_y = data.get('y', self.logic.y)
                self.logic.set_target(float(target_x), float(target_y))
                self._pending_cmd_id = cmd_id
                self._publish_ack(cmd_id, "accepted", f"Moving to ({target_x}, {target_y})")
            elif command == 'stop':
                self.logic.stop()
                self._publish_ack(cmd_id, "done", "Robot stopped")
            else:
                self._publish_ack(cmd_id, "failed", f"Unknown command: {command}")

        except json.JSONDecodeError:
            self.get_logger().error("Failed to parse command JSON")

    def timer_callback(self):
        prev_status = self.logic.get_status()

        # Update logic
        self.logic.update(self.dt)

        # 이동 완료 감지 → done ACK 발행
        curr_status = self.logic.get_status()
        if prev_status == 'moving' and curr_status == 'arrived' and self._pending_cmd_id:
            x, y = self.logic.get_pose()
            self._publish_ack(
                self._pending_cmd_id, "done",
                f"Arrived at ({x:.2f}, {y:.2f})",
                {"x": x, "y": y}
            )
            self._pending_cmd_id = None

        # Publish Pose
        x, y = self.logic.get_pose()
        pose_msg = Pose2D()
        pose_msg.x = x
        pose_msg.y = y
        self.pose_pub.publish(pose_msg)

        # Publish Battery
        battery_msg = Float32()
        battery_msg.data = self.logic.get_battery()
        self.battery_pub.publish(battery_msg)

        # Publish Status
        status_payload = self._generate_envelope('status', {"state": self.logic.get_status()})
        status_msg = String()
        status_msg.data = json.dumps(status_payload)
        self.status_pub.publish(status_msg)

def main(args=None):
    rclpy.init(args=args)
    try:
        node = FakeRobotNode()
        rclpy.spin(node)
    except KeyboardInterrupt:
        pass
    finally:
        if rclpy.ok():
            rclpy.shutdown()

if __name__ == '__main__':
    main()
