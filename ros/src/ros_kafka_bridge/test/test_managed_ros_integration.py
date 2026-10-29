import importlib.util
import json
import os
import threading
import time
import unittest
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer


@unittest.skipUnless(os.environ.get('ROS_KAFKA_TEST_BROKER') and importlib.util.find_spec('rclpy'),
                     'requires ROS2 and an isolated ROS_KAFKA_TEST_BROKER')
class ManagedRosIntegrationTest(unittest.TestCase):
    def test_live_add_delete_preserves_other_robot_movement(self):
        import rclpy
        from confluent_kafka import Producer
        from rclpy.executors import SingleThreadedExecutor
        from rclpy.node import Node
        from rclpy.parameter import Parameter
        from std_msgs.msg import String
        from ros_kafka_bridge.bridge_node import RosKafkaBridgeNode
        from ros_kafka_bridge.simulation_manager import SimulationManager

        fleet = 'managed_' + uuid.uuid4().hex[:8]
        runtime = str(uuid.uuid4())
        first, second = 'managed_first', 'managed_second'
        def config(rid, deleted=False):
            return {'robotId': rid, 'version': 2 if deleted else 1,
                    'desiredState': 'DELETED' if deleted else 'RUNNING',
                    'startX': 0, 'startY': 0, 'speed': 1}
        snapshot = [config(first)]
        reports = {}
        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                body = json.dumps({'fleetId': fleet, 'simulations': snapshot}).encode()
                self.send_response(200); self.end_headers(); self.wfile.write(body)
            def do_POST(self):
                for report in json.loads(self.rfile.read(int(self.headers['Content-Length']))):
                    reports[report['robotId']] = report
                self.send_response(204); self.end_headers()
            def log_message(self, *_):
                pass
        server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        http_thread = threading.Thread(target=server.serve_forever, daemon=True)
        http_thread.start()
        rclpy.init(args=[])
        executor = SingleThreadedExecutor()
        bridge = RosKafkaBridgeNode(managed=True, runtime_id=runtime, use_global_arguments=False,
                                   parameter_overrides=[Parameter('fleet_id', value=fleet),
                                   Parameter('kafka_broker', value=os.environ['ROS_KAFKA_TEST_BROKER'])])
        manager = SimulationManager(executor, bridge, 'test-only',
                                    f'http://127.0.0.1:{server.server_port}', fleet, runtime)
        observer = Node('managed_test_observer', use_global_arguments=False)
        acks = []
        observer.create_subscription(String, f'/fleet/{first}/ack', lambda msg: acks.append(json.loads(msg.data)), 10)
        for node in (bridge, manager, observer):
            executor.add_node(node)
        spin_thread = threading.Thread(target=executor.spin, daemon=True)
        spin_thread.start()
        def wait(predicate):
            deadline = time.monotonic() + 30
            while time.monotonic() < deadline:
                if predicate(): return
                time.sleep(0.05)
            self.fail('managed ROS convergence timed out')
        try:
            wait(lambda: reports.get(first, {}).get('state') == 'RUNNING')
            original = manager.robots[first]
            command_id = str(uuid.uuid4())
            producer = Producer({'bootstrap.servers': os.environ['ROS_KAFKA_TEST_BROKER']})
            producer.produce('cmd.robot', key=first, value=json.dumps({
                'fleet_id': fleet, 'robot_id': first, 'cmd_id': command_id, 'command': 'move_to',
                'data': {'x': 100, 'y': 0}, 'simulation_version': 1, 'simulation_runtime_id': runtime}))
            self.assertEqual(0, producer.flush(10))
            wait(lambda: original.logic.get_pose()[0] > 0)
            before = original.logic.get_pose()[0]
            snapshot = [config(first), config(second)]
            wait(lambda: reports.get(second, {}).get('state') == 'RUNNING')
            self.assertIs(original, manager.robots[first])
            snapshot = [config(first), config(second, deleted=True)]
            wait(lambda: reports.get(second, {}).get('state') == 'DELETED')
            self.assertNotIn(second, manager.robots)
            self.assertGreater(original.logic.get_pose()[0], before)
            self.assertTrue(any(ack['cmd_id'] == command_id and ack['status'] == 'accepted' for ack in acks))
        finally:
            executor.shutdown(timeout_sec=5)
            spin_thread.join(timeout=5)
            manager.destroy_node()
            bridge.destroy_node()
            observer.destroy_node()
            server.shutdown(); server.server_close(); http_thread.join(timeout=5)
            rclpy.shutdown()


@unittest.skipUnless(importlib.util.find_spec('rclpy'), 'requires ROS2 imports')
class ManagedHttpBatchTest(unittest.TestCase):
    def test_status_reports_are_batched_without_dropping_entries(self):
        import queue
        from types import SimpleNamespace
        from unittest.mock import Mock
        from ros_kafka_bridge.simulation_manager import SimulationManager
        reports = queue.Queue()
        reports.put([{'robotId': f'robot_{i}'} for i in range(201)])
        runtime = SimpleNamespace(stop_event=Mock(), snapshots=queue.Queue(maxsize=1), reports=reports,
                                  runtime_id='current-runtime', fleet_id='default',
                                  replace_latest=SimulationManager.replace_latest)
        runtime.stop_event.is_set.side_effect = [False, True]
        runtime.request = Mock(return_value={'fleetId': 'default', 'simulations': []})
        SimulationManager.sync(runtime)
        batches = [call.args[1] for call in runtime.request.call_args_list if len(call.args) == 2]
        self.assertEqual([100, 100, 1], [len(batch) for batch in batches])
        self.assertEqual(201, sum(len(batch) for batch in batches))
        self.assertTrue(all(report['runtimeId'] == 'current-runtime' for batch in batches for report in batch))
