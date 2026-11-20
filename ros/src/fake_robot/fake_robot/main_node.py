import rclpy
from rclpy.node import Node
from geometry_msgs.msg import Pose2D
from std_msgs.msg import Float32, String
import json
import uuid
import datetime

from .runtime_lease import topic_prefix
from .logic import FakeRobotLogic
from .commands import RobotCommands
from .durability import journal_from_env
import os
import sqlite3

class FakeRobotNode(Node):
    def __init__(self, node_name='fake_robot_node', lease=None, **kwargs):
        self.lease = lease
        super().__init__(node_name, **kwargs)

        # Declare parameters
        self.declare_parameter('robot_id', 'robot_1')
        self.declare_parameter('fleet_id', os.environ.get('FLEET_ID', 'default'))
        self.declare_parameter('simulation_runtime_id', '')
        self.declare_parameter('simulation_server_id', '')
        self.declare_parameter('publish_rate_hz', 2.0)
        self.declare_parameter('speed', 1.0)
        self.declare_parameter('start_x', 0.0)
        self.declare_parameter('start_y', 0.0)
        self.declare_parameter('battery_drain_rate', 0.1)
        self.declare_parameter('command_dedup_ttl_seconds', 300.0)
        self.declare_parameter('command_dedup_max_entries', 10000)

        # Get parameters
        self.journal = journal_from_env()
        self.delivery_healthy = True
        self.fleet_id = self.get_parameter('fleet_id').value
        self.robot_id = self.get_parameter('robot_id').value
        if self.journal:
            self.journal.recover(self.fleet_id, self.robot_id)
        publish_rate_hz = self.get_parameter('publish_rate_hz').value
        speed = self.get_parameter('speed').value
        start_x = self.get_parameter('start_x').value
        start_y = self.get_parameter('start_y').value
        battery_drain_rate = self.get_parameter('battery_drain_rate').value

        # Initialize Logic
        self.logic = FakeRobotLogic(
            x=start_x, y=start_y, speed=speed, battery=100.0, battery_drain_rate=battery_drain_rate
        )
        self.commands = RobotCommands(
            self.logic,
            ttl_seconds=self.get_parameter('command_dedup_ttl_seconds').value,
            max_entries=self.get_parameter('command_dedup_max_entries').value,
        )

        prefix=topic_prefix(self.robot_id, self.get_parameter('simulation_runtime_id').value if lease else None)

        # Publishers
        self.pose_pub = self.create_publisher(Pose2D, f'{prefix}/pose', 10)
        self.battery_pub = self.create_publisher(Float32, f'{prefix}/battery', 10)
        self.status_pub = self.create_publisher(String, f'{prefix}/status', 10)
        self.ack_pub = self.create_publisher(String, f'{prefix}/ack', 10)

        # Subscriber
        self.cmd_sub = self.create_subscription(
            String, f'{prefix}/cmd', self.cmd_callback, 10
        )

        # Timer
        self.dt = 1.0 / publish_rate_hz
        self.timer = self.create_timer(self.dt, self.timer_callback)

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
        if self.lease and not self.lease.active():
            self.logic.stop()
            return
        try:
            cmd_payload = json.loads(msg.data)
            cmd_id = cmd_payload.get('cmd_id')
            command = cmd_payload.get('command')
            data = cmd_payload.get('data', {})

            runtime = self.get_parameter('simulation_runtime_id').value
            if runtime and (cmd_payload.get('simulation_runtime_id') != runtime or
                            cmd_payload.get('fleet_id') != self.fleet_id or cmd_payload.get('robot_id') != self.robot_id or
                            self.lease and (type(cmd_payload.get('simulation_epoch')) is not int or cmd_payload.get('simulation_epoch') != self.lease.epoch)):
                raise ValueError('Command belongs to another runtime generation')
            server = self.get_parameter('simulation_server_id').value
            if server and cmd_payload.get('simulation_server_id') != server:
                raise ValueError('Command belongs to another execution server')
            self.get_logger().info(f"Received command: {command}")

            if self.journal:
                if cmd_payload.get('fleet_id') != self.fleet_id or cmd_payload.get('robot_id') != self.robot_id:
                    raise ValueError('Command belongs to another robot')
                runtime = self.get_parameter('simulation_runtime_id').value
                if runtime and cmd_payload.get('simulation_runtime_id') != runtime:
                    raise ValueError('Command belongs to a previous runtime')
                for ack in self.journal.execute(cmd_payload, lambda: self._apply_command(cmd_id, command, data)):
                    self._publish_durable_ack(ack)
            else:
                for ack in self._apply_command(cmd_id, command, data):
                    self._publish_ack(ack.cmd_id, ack.status, ack.message)

        except (sqlite3.Error, OSError, RuntimeError) as error:
            self.delivery_healthy = False
            self.logic.stop()
            self.get_logger().error(f'Durable command storage unavailable: {type(error).__name__}')
        except (ValueError, TypeError, AttributeError, OverflowError, KeyError) as error:
            self.get_logger().error(f"Invalid command: {error}")

    def _apply_command(self,cmd_id,command,data):
        """저널 기록 후 실제 동작 적용 직전에도 로컬 실행 기한을 확인한다."""
        if self.lease and not self.lease.active():
            self.logic.stop()
            raise RuntimeError('Runtime lease expired')
        return self.commands.handle(cmd_id,command,data)

    def _publish_durable_ack(self, payload):
        msg = String()
        msg.data = json.dumps(payload, allow_nan=False)
        self.ack_pub.publish(msg)

    def destroy_node(self):
        if self.journal:
            try:
                self.journal.recover(self.fleet_id, self.robot_id)
            finally:
                self.journal.close()
        return super().destroy_node()

    def timer_callback(self):
        if self.lease and not self.lease.active():
            self.logic.stop()
            return
        # Update logic
        self.logic.update(self.dt)

        # 이동 완료 감지 → done ACK 발행
        ack = self.commands.complete_move()
        if ack is not None:
            x, y = self.logic.get_pose()
            if self.journal:
                try:
                    for payload in self.journal.record([ack], {"x": x, "y": y}):
                        self._publish_durable_ack(payload)
                except (sqlite3.Error, OSError, RuntimeError):
                    self.delivery_healthy = False
                    self.logic.stop()
            else:
                self._publish_ack(ack.cmd_id, ack.status, ack.message, {"x": x, "y": y})

        if self.lease and not self.lease.active():
            self.logic.stop()
            return
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
