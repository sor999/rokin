import json
import os
import threading
import time
import unittest
import uuid


@unittest.skipUnless(os.environ.get('ROS_KAFKA_TEST_BROKER'), 'requires isolated Kafka and ROS2')
class RosCommandIntegrationTest(unittest.TestCase):
    def test_replays_do_not_restart_or_interrupt_robot_motion(self):
        import rclpy
        from rclpy.executors import SingleThreadedExecutor
        from rclpy.node import Node
        from std_msgs.msg import String
        from confluent_kafka import Producer
        from confluent_kafka.admin import AdminClient, NewTopic
        from fake_robot.main_node import FakeRobotNode
        from ros_kafka_bridge.bridge_node import RosKafkaBridgeNode

        broker = os.environ['ROS_KAFKA_TEST_BROKER']
        fleet = 'smoke-' + uuid.uuid4().hex
        admin = AdminClient({'bootstrap.servers': broker})
        existing = admin.list_topics(timeout=10).topics
        topics = ('cmd.robot', 'dlq.command', 'ack.robot', 'telemetry.pose', 'telemetry.battery', 'telemetry.status')
        for future in admin.create_topics([NewTopic(topic, 3, 1) for topic in topics if topic not in existing]).values():
            future.result(timeout=15)
        rclpy.init(args=['--ros-args', '-p', 'kafka_broker:=' + broker,
                        '-p', 'fleet_id:=' + fleet, '-p', 'robot_ids:=[robot_1]'])
        nodes = []
        executor = SingleThreadedExecutor()
        spin = None
        try:
            robot = FakeRobotNode()
            nodes.append(robot)
            bridge = RosKafkaBridgeNode()
            nodes.append(bridge)
            observer = Node('command_test_observer')
            nodes.append(observer)
            acks = []
            observer.create_subscription(String, '/fleet/robot_1/ack', lambda msg: acks.append(json.loads(msg.data)), 10)
            for node in nodes:
                executor.add_node(node)
            spin = threading.Thread(target=executor.spin, daemon=True)
            spin.start()

            def wait_for(predicate):
                deadline = time.monotonic() + 15
                while time.monotonic() < deadline:
                    if predicate():
                        return
                    time.sleep(0.05)
                self.fail('Timed out waiting for ROS/Kafka command result')

            self.assertTrue(bridge._consumer.ready.wait(15))
            wait_for(lambda: robot.ack_pub.get_subscription_count() >= 2
                     and bridge._cmd_publishers['robot_1'].get_subscription_count() >= 1)
            producer = Producer({'bootstrap.servers': broker})

            def send(cmd_id, action, data):
                command = {'cmd_id': cmd_id, 'fleet_id': fleet, 'robot_id': 'robot_1',
                           'command': action, 'data': data}
                producer.produce('cmd.robot', key='robot_1', value=json.dumps(command).encode())
                self.assertEqual(0, producer.flush(10))

            def count(cmd_id, status):
                return sum(ack['cmd_id'] == cmd_id and ack['status'] == status for ack in list(acks))

            move_id, stop_id, next_move_id = (str(uuid.uuid4()) for _ in range(3))
            send(move_id, 'move_to', {'x': 100, 'y': 0})
            wait_for(lambda: count(move_id, 'accepted') == 1)
            send(stop_id, 'stop', {})
            wait_for(lambda: count(stop_id, 'done') == 1)
            stopped = robot.logic.get_pose()
            previous = count(move_id, 'failed')
            send(move_id, 'move_to', {'x': 100, 'y': 0})
            wait_for(lambda: count(move_id, 'failed') > previous)
            self.assertEqual('idle', robot.logic.get_status())
            self.assertEqual(stopped, robot.logic.get_pose())
            send(next_move_id, 'move_to', {'x': 100, 'y': 0})
            wait_for(lambda: count(next_move_id, 'accepted') == 1)
            send(stop_id, 'stop', {})
            wait_for(lambda: count(stop_id, 'done') == 2)
            self.assertEqual('moving', robot.logic.get_status())
            self.assertEqual(next_move_id, robot.commands.pending_move_id)
        finally:
            executor.shutdown(timeout_sec=5)
            if spin is not None:
                spin.join(timeout=5)
            for node in reversed(nodes):
                node.destroy_node()
            if rclpy.ok():
                rclpy.shutdown()
