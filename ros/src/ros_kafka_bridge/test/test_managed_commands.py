import unittest
import uuid
from ros_kafka_bridge.commands import ManagedFleetCommandRouter, CommandRejected


class ManagedCommandTest(unittest.TestCase):
    def setUp(self):
        self.sent = []
        self.router = ManagedFleetCommandRouter('default', self.sent.append, 'runtime-a')
        self.router.add('robot_4', 1)
        self.command = {'fleet_id': 'default', 'robot_id': 'robot_4', 'cmd_id': str(uuid.uuid4()),
                        'command': 'stop', 'data': {}, 'simulation_version': 1, 'simulation_runtime_id': 'runtime-a'}

    def test_membership_changes_keep_group_and_existing_command_path(self):
        group = self.router.consumer_group('bridge')
        self.router.add('robot_5', 1)
        self.router.dispatch(self.command)
        self.router.remove('robot_5')
        self.assertEqual(group, self.router.consumer_group('bridge'))
        self.assertEqual([self.command], self.sent)

    def test_previous_runtime_and_deleted_robot_commands_are_rejected(self):
        self.command['simulation_runtime_id'] = 'old-runtime'
        with self.assertRaises(CommandRejected):
            self.router.dispatch(self.command)
        self.command['simulation_runtime_id'] = 'runtime-a'
        self.router.remove('robot_4')
        with self.assertRaises(CommandRejected):
            self.router.dispatch(self.command)
        self.assertEqual([], self.sent)

    def test_old_config_version_cannot_execute(self):
        self.router.add('robot_4', 2)
        with self.assertRaises(CommandRejected):
            self.router.dispatch(self.command)
