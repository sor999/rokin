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
from fake_robot.runtime_lease import RuntimeLease
from fake_robot.durability import journal_from_env
from .durable_delivery import DurableBridgeDelivery
from .producer import KafkaTelemetryProducer
from .bridge_node import RosKafkaBridgeNode
from .reconciliation import SimulationReconciler

logger = logging.getLogger(__name__)


class SimulationManager(Node):
    """HTTP 스레드와 ROS executor를 분리해 저장된 실행 목록으로 수렴한다."""

    def __init__(self, executor, bridge, token, api_url, fleet_id, runtime_id, lease=None, bridge_factory=None):
        super().__init__('simulation_manager_'+runtime_id.replace('-','')[:8], use_global_arguments=False)
        self.lease=lease
        self.bridge_factory=bridge_factory
        self.retired=False
        self.recovery_journal=None
        self.recovery_delivery=None
        self.recovery_producer=None
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
        self.reconciler = SimulationReconciler(self.start_robot, self.stop_robot,
                                               lambda rid: self.bridge is not None and self.bridge.robot_ready(rid))
        if lease:
            self.recovery_journal=journal_from_env()
            if self.recovery_journal:
                self.recovery_journal.recover_fleet(fleet_id)
                self.recovery_producer=KafkaTelemetryProducer(os.environ.get('KAFKA_BROKER_URL','kafka:29092'))
                self.recovery_delivery=DurableBridgeDelivery(self.recovery_journal,self.recovery_producer,None,{},self.get_logger())
                self.recovery_delivery.start()
            self.lease_thread=threading.Thread(target=self.renew,name='runtime-lease-http',daemon=True)
            self.lease_thread.start()
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
            payload=response.read()
            return json.loads(payload) if payload else None

    def renew(self):
        """권한 갱신을 목록 조회 및 보고와 분리해 HTTP 지연의 영향을 제한한다."""
        while not self.stop_event.is_set():
            try:
                if self.lease.retired or (self.lease.epoch is not None and not self.lease.active()):
                    return
                started=self.lease.clock()
                response=self.request('/internal/simulations/lease',{'runtimeId':self.runtime_id})
                previous=self.lease.epoch
                granted=self.lease.accept(response,started,self.fleet_id)
                if granted and previous is None:
                    logger.info('Runtime lease acquired runtime=%s epoch=%s',self.runtime_id,self.lease.epoch)
            except Exception:
                logger.warning('Runtime lease renewal failed; keeping only existing local deadline')
            self.stop_event.wait(2)

    def sync(self):
        registered = False
        while not self.stop_event.is_set():
            try:
                if self.lease and not self.lease.active():
                    self.stop_event.wait(0.2)
                    continue
                if not self.lease and not registered:
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
                                 [{**report, 'runtimeId': self.runtime_id, **({'runtimeEpoch':self.lease.epoch} if self.lease else {})} for report in reports[offset:offset + 100]])
            except Exception:
                logger.warning('Simulation API sync failed; keeping current robots')
            self.stop_event.wait(2)

    def start_robot(self, config):
        if self.lease and not self.lease.active():
            raise RuntimeError('Runtime lease expired')
        rid = config['robotId']
        node = FakeRobotNode(node_name='sim_' + rid+('_'+self.runtime_id.replace('-','')[:8] if self.lease else ''), lease=self.lease, use_global_arguments=False, parameter_overrides=[
            Parameter('robot_id', value=rid), Parameter('fleet_id', value=self.fleet_id), Parameter('simulation_runtime_id', value=self.runtime_id), Parameter('start_x', value=float(config['startX'])),
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
        if self.lease:
            if not self.lease.active():
                if self.lease.retired:
                    self.retired=True
                    for node in self.robots.values():
                        node.logic.stop()
                return
            if self.bridge is None:
                self.bridge=self.bridge_factory(self.runtime_id,self.lease)
                self.executor.add_node(self.bridge)
        try:
            snapshot = self.snapshots.get_nowait()
        except queue.Empty:
            return
        reports = self.reconciler.reconcile(snapshot)
        if self.bridge.journal:
            for report in reports:
                node = self.robots.get(report['robotId'])
                if node is not None and not node.delivery_healthy:
                    report['state'] = 'FAILED'
                    report['error'] = 'Durable command storage unavailable'
        self.replace_latest(self.reports, reports)

    def destroy_node(self):
        self.stop_event.set()
        if self.lease:
            self.lease.revoke()
            self.lease_thread.join(timeout=7)
        self.thread.join(timeout=7)
        for rid in list(self.robots):
            self.stop_robot(rid)
        if self.recovery_delivery:
            self.recovery_delivery.stop()
            self.recovery_journal.close()
            self.recovery_producer.flush()
        super().destroy_node()


def main(args=None):
    if os.environ.get('SIMULATOR_DISTRIBUTED_ENABLED', 'false').lower() == 'true':
        from .distributed_manager import main as distributed_main
        return distributed_main(args)
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
        lease=RuntimeLease(runtime_id) if os.environ.get('SIMULATOR_LEASE_ENABLED','false').lower()=='true' else None
        def factory(runtime,guard):
            return RosKafkaBridgeNode(managed=True,runtime_id=runtime,lease=guard,
                                      use_global_arguments=False,parameter_overrides=[
                Parameter('fleet_id',value=fleet_id),
                Parameter('kafka_broker',value=os.environ.get('KAFKA_BROKER_URL','kafka:29092'))])
        bridge=None if lease else factory(runtime_id,None)
        manager = SimulationManager(executor, bridge, token,
                                    os.environ.get('SIMULATOR_API_URL', 'http://api:8080'), fleet_id, runtime_id,
                                    lease=lease,bridge_factory=factory)
        if bridge:
            executor.add_node(bridge)
        executor.add_node(manager)
        while rclpy.ok() and not manager.retired:
            executor.spin_once(timeout_sec=0.2)
    except KeyboardInterrupt:
        pass
    finally:
        try:
            if manager is not None:
                manager.destroy_node()
        finally:
            try:
                bridge=manager.bridge if manager is not None else bridge
                if bridge is not None:
                    bridge.destroy_node()
            finally:
                executor.shutdown()
                if rclpy.ok():
                    rclpy.shutdown()
