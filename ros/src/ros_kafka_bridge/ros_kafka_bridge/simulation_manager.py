import json
import logging
import os
import queue
import threading
import uuid
from urllib.request import Request, urlopen

import rclpy
from rclpy.executors import SingleThreadedExecutor
from rclpy.node import Node
from rclpy.parameter import Parameter

from fake_robot.main_node import FakeRobotNode
from .bridge_node import RosKafkaBridgeNode
from .reconciliation import SimulationReconciler

logger = logging.getLogger(__name__)


class SimulationManager(Node):
    """HTTP 스레드와 ROS executor를 분리해 저장된 실행 목록으로 수렴한다."""

    def __init__(self, executor, bridge, token, api_url, fleet_id, runtime_id):
        super().__init__('simulation_manager', use_global_arguments=False)
        self.executor = executor
        self.bridge = bridge
        self.token = token
        self.api_url = api_url.rstrip('/')
        self.fleet_id = fleet_id
        self.runtime_id = runtime_id
        self.robots = {}
        self.snapshots = queue.Queue(maxsize=1)
        self.reports = queue.Queue(maxsize=1)
        self.stop_event = threading.Event()
        self.reconciler = SimulationReconciler(self.start_robot, self.stop_robot, bridge.robot_ready)
        self.create_timer(0.2, self.tick)
        self.thread = threading.Thread(target=self.sync, daemon=True, name='simulation-http')
        self.thread.start()

    @staticmethod
    def replace_latest(target, value):
        try:
            target.get_nowait()
        except queue.Empty:
            pass
        target.put_nowait(value)

    def request(self, path, body=None):
        payload = None if body is None else json.dumps(body, allow_nan=False).encode()
        request = Request(self.api_url + path, data=payload, headers={
            'X-Simulator-Token': self.token, 'Content-Type': 'application/json'})
        with urlopen(request, timeout=3) as response:
            return json.loads(response.read()) if body is None else None

    def sync(self):
        registered = False
        while not self.stop_event.is_set():
            try:
                if not registered:
                    self.request('/internal/simulations/runtime', {'runtimeId': self.runtime_id})
                    registered = True
                snapshot = self.request('/internal/simulations')
                if snapshot['fleetId'] != self.fleet_id:
                    raise ValueError('Fleet configuration mismatch')
                SimulationReconciler.validate(snapshot['simulations'])
                self.replace_latest(self.snapshots, snapshot['simulations'])
                try:
                    reports = self.reports.get_nowait()
                except queue.Empty:
                    reports = []
                for offset in range(0, len(reports), 100):
                    self.request('/internal/simulations/reports',
                                 [{**report, 'runtimeId': self.runtime_id} for report in reports[offset:offset + 100]])
            except Exception:
                logger.warning('Simulation API sync failed; keeping current robots')
            self.stop_event.wait(2)

    def start_robot(self, config):
        rid = config['robotId']
        node = FakeRobotNode(node_name='sim_' + rid, use_global_arguments=False, parameter_overrides=[
            Parameter('robot_id', value=rid), Parameter('start_x', value=float(config['startX'])),
            Parameter('start_y', value=float(config['startY'])), Parameter('speed', value=float(config['speed']))])
        try:
            with self.bridge._router.ownership_lock:
                self.bridge.attach_robot(rid)
                self.executor.add_node(node)
                self.bridge._router.add(rid, config['version'])
                self.robots[rid] = node
        except Exception:
            self.bridge.detach_robot(rid)
            self.executor.remove_node(node)
            node.destroy_node()
            raise

    def stop_robot(self, rid):
        with self.bridge._router.ownership_lock:
            self.bridge._router.remove(rid)
            node = self.robots.get(rid)
            if node is not None:
                self.executor.remove_node(node)
                node.destroy_node()
                del self.robots[rid]
            self.bridge.detach_robot(rid)

    def tick(self):
        try:
            snapshot = self.snapshots.get_nowait()
        except queue.Empty:
            return
        reports = self.reconciler.reconcile(snapshot)
        self.replace_latest(self.reports, reports)

    def destroy_node(self):
        self.stop_event.set()
        self.thread.join(timeout=7)
        for rid in list(self.robots):
            self.stop_robot(rid)
        super().destroy_node()


def main(args=None):
    logging.basicConfig(level=logging.INFO)
    token = os.environ.get('SIMULATOR_INTERNAL_TOKEN', '')
    if not token:
        raise ValueError('SIMULATOR_INTERNAL_TOKEN is required')
    fleet_id = os.environ.get('FLEET_ID', 'default')
    runtime_id = str(uuid.uuid4())
    rclpy.init(args=args)
    executor = SingleThreadedExecutor()
    bridge = None
    manager = None
    try:
        bridge = RosKafkaBridgeNode(managed=True, runtime_id=runtime_id,
                                   use_global_arguments=False, parameter_overrides=[
            Parameter('fleet_id', value=fleet_id),
            Parameter('kafka_broker', value=os.environ.get('KAFKA_BROKER_URL', 'kafka:29092'))])
        manager = SimulationManager(executor, bridge, token,
                                    os.environ.get('SIMULATOR_API_URL', 'http://api:8080'), fleet_id, runtime_id)
        executor.add_node(bridge)
        executor.add_node(manager)
        executor.spin()
    except KeyboardInterrupt:
        pass
    finally:
        try:
            if manager is not None:
                manager.destroy_node()
        finally:
            try:
                if bridge is not None:
                    bridge.destroy_node()
            finally:
                executor.shutdown()
                if rclpy.ok():
                    rclpy.shutdown()
