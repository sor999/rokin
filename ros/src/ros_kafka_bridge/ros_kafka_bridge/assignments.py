"""분산 실행의 순수 배정 상태와 로컬 임대. ROS 객체는 소유하지 않는다."""
import threading

from fake_robot.runtime_lease import RuntimeLease, runtime_clock, topic_prefix
from .reconciliation import SimulationReconciler


class AssignmentSet:
    def __init__(self, fleet, runtime, clock=runtime_clock):
        self.fleet = fleet
        self.runtime = runtime
        self.clock = clock
        self.server = RuntimeLease(runtime, clock=clock)
        self.lock = threading.RLock()
        self.entries = {}
        self.guards = {}
        self.state = 'STARTING'
        self.released_ids = set()

    def accept(self, snapshot, started):
        if (not isinstance(snapshot, dict) or snapshot.get('fleetId') != self.fleet
                or snapshot.get('runtimeId') != self.runtime
                or snapshot.get('state') not in ('ACTIVE', 'DRAINING', 'RETIRED')
                or not isinstance(snapshot.get('assignments'), list)
                or len(snapshot['assignments']) > 10000):
            raise ValueError('Invalid assignment snapshot')
        assignments = snapshot['assignments']
        SimulationReconciler.validate(assignments)
        executions = set()
        for assignment in assignments:
            if (assignment.get('state') not in ('ASSIGNED', 'RELEASING')
                    or type(assignment.get('assignmentVersion')) is not int
                    or assignment['assignmentVersion'] < 1):
                raise ValueError('Invalid assignment generation')
            # 검증과 동시에 새 DDS 경로가 UUID 규칙을 따르는지 확인한다.
            topic_prefix(assignment['robotId'], assignment.get('executionId'))
            execution = assignment.get('executionId')
            if execution is None or execution in executions:
                raise ValueError('Missing or duplicate execution ID')
            executions.add(execution)
        with self.lock:
            retired = snapshot['state'] == 'RETIRED'
            granted = self.server.accept(dict(
                fleetId=self.fleet, runtimeId=self.runtime, epoch=1,
                ttlMillis=snapshot['ttlMillis'], granted=not retired, retired=retired),
                started, self.fleet)
            if not granted:
                self.revoke()
                return False
            desired = {entry['robotId']: dict(entry) for entry in assignments
                       if entry['executionId'] not in self.released_ids}
            for robot, guard in list(self.guards.items()):
                if robot not in desired or desired[robot]['executionId'] != guard.runtime:
                    guard.revoke()
                    del self.guards[robot]
            for robot, entry in desired.items():
                guard = self.guards.get(robot)
                if guard is None:
                    guard = RuntimeLease(entry['executionId'], clock=self.clock)
                    self.guards[robot] = guard
                valid = guard.accept(dict(
                    fleetId=self.fleet, runtimeId=entry['executionId'],
                    epoch=entry['assignmentVersion'], ttlMillis=snapshot['ttlMillis'],
                    granted=True, retired=False), started, self.fleet)
                if not valid:
                    self.revoke()
                    return False
            self.entries = desired
            self.state = snapshot['state']
            return True

    def active(self, robot=None):
        with self.lock:
            if not self.server.active():
                return False
            return robot is None or (robot in self.guards and self.guards[robot].active())

    def snapshot(self):
        with self.lock:
            return {robot: dict(entry) for robot, entry in self.entries.items()}

    def execution(self, robot):
        with self.lock:
            return self.entries.get(robot, {}).get('executionId')

    def guard(self, robot):
        with self.lock:
            return self.guards.get(robot)

    def revoke(self):
        with self.lock:
            self.server.revoke()
            for guard in self.guards.values():
                guard.revoke()

    def released(self, robot, execution):
        with self.lock:
            if self.execution(robot) == execution:
                self.released_ids.add(execution)
                self.guards[robot].revoke()
                if len(self.released_ids) >= 10000:
                    self.server.revoke()
