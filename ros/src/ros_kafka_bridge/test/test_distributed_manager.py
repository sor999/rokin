import importlib.util
import threading
import unittest
import uuid
from unittest.mock import Mock, patch


@unittest.skipUnless(importlib.util.find_spec('rclpy'), 'requires ROS2')
class DistributedManagerTest(unittest.TestCase):
    def test_idle_release_preserves_other_robot_and_moving_robot_waits_for_completion(self):
        import rclpy
        from rclpy.executors import SingleThreadedExecutor
        from rclpy.node import Node
        from types import SimpleNamespace
        from ros_kafka_bridge.assignments import AssignmentSet
        from ros_kafka_bridge.distributed_manager import DistributedManager

        class Bridge(Node):
            def __init__(self, **kwargs):
                super().__init__('test_distribution_bridge', use_global_arguments=False)
                self._router = SimpleNamespace(
                    ownership_lock=threading.RLock(), add=Mock(), remove=Mock())
                self.attach_robot = Mock()
                self.detach_robot = Mock()
                self.robot_ready = Mock(return_value=True)

        def robot(name, execution, state='ASSIGNED'):
            return dict(robotId=name, executionId=execution, assignmentVersion=1, state=state,
                        version=1, desiredState='RUNNING', startX=0, startY=0, speed=1)

        rclpy.init()
        executor = SingleThreadedExecutor()
        manager = None
        try:
            with patch('ros_kafka_bridge.distributed_manager.RosKafkaBridgeNode', Bridge), \
                    patch.object(DistributedManager, 'sync', return_value=None), \
                    patch.object(DistributedManager, 'renew', return_value=None), \
                    patch('ros_kafka_bridge.distributed_manager.journal_from_env', return_value=None):
                manager = DistributedManager(executor, 'test', str(uuid.uuid4()), 20, 'test-only', 'http://unused')
            now = [100.0]
            manager.assignments = AssignmentSet('test', manager.runtime, clock=lambda: now[0])
            first_execution = str(uuid.uuid4())
            second_execution = str(uuid.uuid4())

            def apply(entries):
                manager.assignments.accept(dict(
                    fleetId='test', runtimeId=manager.runtime, state='ACTIVE', ttlMillis=12000,
                    assignments=entries), now[0])
                manager.tick()

            first = robot('one', first_execution)
            second = robot('two', second_execution)
            apply([first])
            original = manager.nodes['one']
            original.commands.handle('move', 'move_to', dict(x=100, y=0))
            original.timer_callback()
            before = original.logic.get_pose()[0]
            apply([first, second])
            self.assertIs(original, manager.nodes['one'])
            self.assertIn('two', manager.nodes)

            releasing_first = robot('one', first_execution, 'RELEASING')
            apply([releasing_first, second])
            self.assertIs(original, manager.nodes['one'])
            original.timer_callback()
            self.assertGreater(original.logic.get_pose()[0], before)
            self.assertTrue(manager.releases.empty())

            releasing_second = robot('two', second_execution, 'RELEASING')
            apply([first, releasing_second])
            self.assertNotIn('two', manager.nodes)
            self.assertIs(original, manager.nodes['one'])
            self.assertEqual(second_execution, manager.releases.get_nowait()['executionId'])

            original.commands.handle('stop', 'stop', {})
            apply([releasing_first])
            self.assertNotIn('one', manager.nodes)
            self.assertEqual(first_execution, manager.releases.get_nowait()['executionId'])
            apply([releasing_first])
            self.assertNotIn('one', manager.nodes)
            self.assertTrue(manager.releases.empty())
        finally:
            if manager:
                manager.destroy_node()
            executor.shutdown()
            rclpy.shutdown()

    def test_report_request_cannot_block_independent_lease_pulses(self):
        import rclpy
        from rclpy.executors import SingleThreadedExecutor
        from rclpy.node import Node
        from types import SimpleNamespace
        from ros_kafka_bridge.distributed_manager import DistributedManager

        class Bridge(Node):
            def __init__(self, **kwargs):
                super().__init__('test_pulse_bridge', use_global_arguments=False)
                self._router = SimpleNamespace(ownership_lock=threading.RLock())

        report_blocked = threading.Event()
        release_report = threading.Event()
        pulse_while_blocked = threading.Event()

        def request(manager, path, body):
            if path == '/internal/distribution/pulse':
                if report_blocked.is_set():
                    pulse_while_blocked.set()
                return dict(fleetId='test', runtimeId=manager.runtime,
                            state='ACTIVE', ttlMillis=6000, assignments=[])
            if path == '/internal/simulations/reports':
                report_blocked.set()
                release_report.wait(5)
                return None
            self.fail('Unexpected request path ' + path)

        rclpy.init()
        executor = SingleThreadedExecutor()
        manager = None
        try:
            with patch('ros_kafka_bridge.distributed_manager.RosKafkaBridgeNode', Bridge), \
                    patch.object(DistributedManager, 'request', request), \
                    patch('ros_kafka_bridge.distributed_manager.journal_from_env', return_value=None):
                manager = DistributedManager(executor, 'test', str(uuid.uuid4()), 20, 'test-only', 'http://unused')
                manager.reports.put_nowait([dict(robotId='one', state='STARTING')])
                self.assertTrue(report_blocked.wait(3))
                self.assertTrue(pulse_while_blocked.wait(2))
                self.assertTrue(manager.assignments.active())
        finally:
            release_report.set()
            if manager:
                manager.destroy_node()
            executor.shutdown()
            rclpy.shutdown()
