"""배정과 HTTP 동기화는 분리하고 모든 ROS 자원 변경은 executor에서 수행한다."""
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

from fake_robot.durability import journal_from_env
from fake_robot.main_node import FakeRobotNode
from .assignments import AssignmentSet
from .bridge_node import RosKafkaBridgeNode
from .durable_delivery import DurableBridgeDelivery
from .producer import KafkaTelemetryProducer


class DistributedManager(Node):
    def __init__(self, executor, fleet, runtime, capacity, token, api):
        super().__init__('distributed_manager_' + runtime.replace('-', ''), use_global_arguments=False)
        if not token or not 1 <= capacity <= 10000:
            raise ValueError('Server token and valid capacity required')
        self.executor = executor
        self.fleet = fleet
        self.runtime = runtime
        self.capacity = capacity
        self.token = token
        self.api = api.rstrip('/')
        self.assignments = AssignmentSet(fleet, runtime)
        self.nodes = {}
        self.executions = {}
        self.stopping = threading.Event()
        self.reports = queue.Queue(maxsize=1)
        self.releases = queue.Queue(maxsize=10000)
        self.journal = journal_from_env()
        self.recovery = None
        self.recovery_producer = None
        if self.journal:
            self.journal.recover_fleet(fleet)
            self.recovery_producer = KafkaTelemetryProducer(os.environ.get('KAFKA_BROKER_URL', 'kafka:29092'))
            self.recovery = DurableBridgeDelivery(
                self.journal, self.recovery_producer, None, {}, self.get_logger())
            self.recovery.start()
        self.bridge = RosKafkaBridgeNode(
            managed=True, runtime_id=runtime, assignments=self.assignments,
            use_global_arguments=False, parameter_overrides=[
                Parameter('fleet_id', value=fleet),
                Parameter('kafka_broker', value=os.environ.get('KAFKA_BROKER_URL', 'kafka:29092')),
            ])
        executor.add_node(self.bridge)
        self.create_timer(0.2, self.tick)
        self.thread = threading.Thread(target=self.sync, name='distribution-http', daemon=True)
        self.thread.start()

    def request(self, path, body):
        payload = json.dumps(body).encode()
        request = Request(self.api + path, data=payload, headers={
            'X-Simulator-Token': self.token,
            'Content-Type': 'application/json',
        })
        with urlopen(request, timeout=3) as response:
            data = response.read()
            return json.loads(data) if data else None

    def sync(self):
        pending_releases = []
        registered = False
        while not self.stopping.is_set():
            if self.assignments.server.retired:
                return
            try:
                while len(pending_releases) < 100:
                    try:
                        pending_releases.append(self.releases.get_nowait())
                    except queue.Empty:
                        break
                if pending_releases:
                    self.request('/internal/distribution/released', {
                        'runtimeId': self.runtime,
                        'releases': pending_releases,
                    })
                    pending_releases = []
                if (self.assignments.server.epoch is not None
                        and not self.assignments.active()):
                    return
                started = self.assignments.clock()
                snapshot = self.request('/internal/distribution/pulse', {
                    'runtimeId': self.runtime,
                    'capacity': self.capacity,
                })
                if not self.assignments.accept(snapshot, started):
                    return
                if not registered:
                    self.get_logger().info('Execution server registered runtime=' + self.runtime)
                    registered = True
                try:
                    reports = self.reports.get_nowait()
                except queue.Empty:
                    reports = []
                for offset in range(0, len(reports), 100):
                    self.request('/internal/simulations/reports', reports[offset:offset + 100])
            except Exception:
                self.get_logger().warning('Distribution sync failed; keeping existing local deadlines')
            self.stopping.wait(2)

    def stop_robot(self, robot):
        with self.bridge._router.ownership_lock:
            self.bridge._router.remove(robot)
            node = self.nodes.pop(robot, None)
            self.executions.pop(robot, None)
            if node is not None:
                node.logic.stop()
                self.executor.remove_node(node)
                node.destroy_node()
            self.bridge.detach_robot(robot)

    def start_robot(self, assignment):
        robot = assignment['robotId']
        guard = self.assignments.guard(robot)
        if guard is None or not self.assignments.active(robot):
            return
        node = FakeRobotNode(
            node_name='robot_' + assignment['executionId'].replace('-', ''),
            lease=guard, use_global_arguments=False, parameter_overrides=[
                Parameter('fleet_id', value=self.fleet),
                Parameter('robot_id', value=robot),
                Parameter('simulation_runtime_id', value=assignment['executionId']),
                Parameter('simulation_server_id', value=self.runtime),
                Parameter('start_x', value=float(assignment['startX'])),
                Parameter('start_y', value=float(assignment['startY'])),
                Parameter('speed', value=float(assignment['speed'])),
            ])
        try:
            with self.bridge._router.ownership_lock:
                self.bridge.attach_robot(robot)
                self.executor.add_node(node)
                self.bridge._router.add(robot, assignment['version'])
                self.nodes[robot] = node
                self.executions[robot] = assignment['executionId']
        except Exception:
            self.executor.remove_node(node)
            self.bridge.detach_robot(robot)
            node.destroy_node()
            raise

    def tick(self):
        if not self.assignments.active():
            if self.assignments.server.retired:
                for robot in list(self.nodes):
                    self.stop_robot(robot)
            return
        desired = self.assignments.snapshot()
        for robot in list(self.nodes):
            if (robot not in desired
                    or desired[robot]['executionId'] != self.executions[robot]
                    or not self.assignments.active(robot)):
                self.stop_robot(robot)
        reports = []
        for robot, entry in desired.items():
            if not self.assignments.active(robot):
                continue
            if entry['state'] == 'RELEASING':
                node = self.nodes.get(robot)
                pending = self.journal and self.journal.pending_robot(self.fleet, robot)
                idle = node is None or node.logic.get_status() in ('idle', 'arrived')
                if entry['desiredState'] == 'DELETED' or (idle and not pending):
                    self.assignments.released(robot, entry['executionId'])
                    self.stop_robot(robot)
                    self.releases.put_nowait({
                        'robotId': robot,
                        'executionId': entry['executionId'],
                        'assignmentVersion': entry['assignmentVersion'],
                    })
                continue
            state = 'STARTING'
            error = None
            try:
                if robot not in self.nodes:
                    self.start_robot(entry)
                if robot in self.nodes and not self.nodes[robot].delivery_healthy:
                    state = 'FAILED'
                    error = '명령 영속 저장소를 확인할 수 없습니다.'
                elif robot in self.nodes and self.bridge.robot_ready(robot):
                    state = 'RUNNING'
            except Exception:
                state = 'FAILED'
                error = '배정된 로봇 실행을 준비하지 못했습니다.'
            reports.append({
                'robotId': robot,
                'version': entry['version'],
                'state': state,
                'error': error,
                'runtimeId': entry['executionId'],
                'runtimeEpoch': entry['assignmentVersion'],
            })
        try:
            self.reports.get_nowait()
        except queue.Empty:
            pass
        self.reports.put_nowait(reports)

    def destroy_node(self):
        self.stopping.set()
        self.assignments.revoke()
        self.thread.join(timeout=15)
        try:
            for robot in list(self.nodes):
                self.stop_robot(robot)
        finally:
            try:
                self.bridge.destroy_node()
            finally:
                if self.recovery:
                    self.recovery.stop()
                    self.journal.close()
                    self.recovery_producer.flush()
                super().destroy_node()


def main(args=None):
    logging.basicConfig(level=logging.INFO)
    rclpy.init(args=args)
    executor = SingleThreadedExecutor()
    manager = None
    try:
        manager = DistributedManager(
            executor, os.environ.get('FLEET_ID', 'default'), str(uuid.uuid4()),
            int(os.environ.get('SIMULATOR_SERVER_CAPACITY', '20')),
            os.environ.get('SIMULATOR_INTERNAL_TOKEN', ''),
            os.environ.get('SIMULATOR_API_URL', 'http://api:8080'))
        executor.add_node(manager)
        while rclpy.ok() and not manager.assignments.server.retired:
            executor.spin_once(timeout_sec=0.2)
    except KeyboardInterrupt:
        pass
    finally:
        try:
            if manager is not None:
                manager.destroy_node()
        finally:
            executor.shutdown()
            if rclpy.ok():
                rclpy.shutdown()
