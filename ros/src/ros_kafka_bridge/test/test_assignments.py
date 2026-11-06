import importlib.util
import uuid
import unittest

from ros_kafka_bridge.assignments import AssignmentSet
from ros_kafka_bridge.commands import DistributedCommandRouter, CommandRejected


class AssignmentTest(unittest.TestCase):
    def setUp(self):
        self.now = 100.0
        self.server = str(uuid.uuid4())
        self.execution = str(uuid.uuid4())
        self.assignments = AssignmentSet('test', self.server, clock=lambda: self.now)

    def robot(self, robot='one', execution=None, version=1, state='ASSIGNED'):
        return dict(
            robotId=robot, executionId=execution or self.execution, assignmentVersion=version,
            version=1, state=state, desiredState='RUNNING', startX=0, startY=0, speed=1)

    def snapshot(self, robots=None):
        return dict(
            fleetId='test', runtimeId=self.server, state='ACTIVE', ttlMillis=12000,
            assignments=robots if robots is not None else [self.robot()])

    def command(self, **changes):
        command = dict(
            fleet_id='test', robot_id='one', cmd_id=str(uuid.uuid4()), command='move_to', data=dict(x=10, y=0),
            simulation_server_id=self.server, simulation_runtime_id=self.execution,
            simulation_epoch=1, simulation_version=1)
        command.update(changes)
        return command

    def test_repeat_snapshot_keeps_guard_and_other_robot_change_does_not_reset_it(self):
        self.assertTrue(self.assignments.accept(self.snapshot(), self.now))
        guard = self.assignments.guard('one')
        self.now = 103
        second = self.robot('two', str(uuid.uuid4()))
        self.assertTrue(self.assignments.accept(self.snapshot([self.robot(), second]), self.now))
        self.assertIs(guard, self.assignments.guard('one'))
        self.assertTrue(guard.active())
        self.assignments.accept(self.snapshot(), self.now)
        self.assertIsNone(self.assignments.guard('two'))
        self.assertIs(guard, self.assignments.guard('one'))

    def test_release_and_inflight_old_snapshot_cannot_recreate_execution(self):
        self.assignments.accept(self.snapshot(), self.now)
        guard = self.assignments.guard('one')
        self.assignments.released('one', self.execution)
        self.now += 1
        self.assertTrue(self.assignments.accept(self.snapshot(), self.now))
        self.assertFalse(guard.active())
        self.assertNotIn('one', self.assignments.snapshot())
        replacement = self.robot(execution=str(uuid.uuid4()), version=2)
        self.assertTrue(self.assignments.accept(self.snapshot([replacement]), self.now))
        self.assertNotEqual(self.execution, self.assignments.execution('one'))
        self.assertTrue(self.assignments.active('one'))

    def test_late_response_and_retired_server_never_extend_old_permission(self):
        self.assignments.accept(self.snapshot(), self.now)
        self.now = 111
        self.assertFalse(self.assignments.active('one'))
        self.assertFalse(self.assignments.accept(self.snapshot(), self.now))
        self.assertTrue(self.assignments.server.retired)

    def test_router_filters_other_server_before_tokens_and_keeps_group_after_roster_change(self):
        self.assignments.accept(self.snapshot(), self.now)
        delivered = []
        router = DistributedCommandRouter(self.assignments, delivered.append)
        router.add('one', 1)
        group = router.consumer_group('bridge')
        router.dispatch(self.command())
        self.assertEqual(1, len(delivered))
        router.dispatch(self.command(simulation_server_id=str(uuid.uuid4()), simulation_epoch=False))
        self.assertEqual(1, len(delivered))
        for stale in [dict(simulation_epoch=True), dict(simulation_runtime_id=str(uuid.uuid4())),
                      dict(simulation_version=2), dict(simulation_server_id=None), dict(fleet_id=None)]:
            with self.assertRaises(CommandRejected):
                router.dispatch(self.command(**stale))
        router.add('two', 1)
        self.assertEqual(group, router.consumer_group('bridge'))
        self.assertNotEqual(group, DistributedCommandRouter(
            AssignmentSet('test', str(uuid.uuid4()), clock=lambda: self.now), delivered.append
        ).consumer_group('bridge'))

    def test_invalid_snapshot_does_not_refresh_current_deadline(self):
        self.assignments.accept(self.snapshot(), self.now)
        original = self.assignments.server.deadline
        self.now += 1
        invalid = self.snapshot([self.robot(version=True)])
        with self.assertRaises(ValueError):
            self.assignments.accept(invalid, self.now)
        self.assertEqual(original, self.assignments.server.deadline)


class ObservationIdentityTest(unittest.TestCase):
    @unittest.skipUnless(importlib.util.find_spec('rclpy'), 'requires ROS2')
    def test_queued_old_observation_keeps_its_original_execution_when_assignment_changes(self):
        import json
        from types import SimpleNamespace
        from unittest.mock import Mock
        from ros_kafka_bridge.bridge_node import RosKafkaBridgeNode

        old_execution = str(uuid.uuid4())
        new_execution = str(uuid.uuid4())
        assignments = Mock()
        assignments.active.return_value = True
        assignments.execution.return_value = new_execution
        producer = Mock()
        bridge = SimpleNamespace(
            assignments=assignments, _runtime_id='server', lease=None,
            _producer=producer, get_logger=Mock(return_value=Mock()))
        status = SimpleNamespace(data=json.dumps(dict(type='status', data=dict(state='moving'))))
        RosKafkaBridgeNode._on_status(bridge, 'one', status, old_execution)
        self.assertEqual(old_execution, producer.produce.call_args.kwargs['value']['simulation_runtime_id'])
