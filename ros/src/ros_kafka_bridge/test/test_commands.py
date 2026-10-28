import copy
import unittest

from ros_kafka_bridge.commands import CommandRejected, FleetCommandRouter


class FleetRouterTest(unittest.TestCase):
    def setUp(self):
        self.sent = []
        self.router = FleetCommandRouter('site-a', ['robot_1'], self.sent.append)
        self.command = {'cmd_id': '00000000-0000-0000-0000-000000000001',
                        'fleet_id': 'site-a', 'robot_id': 'robot_1',
                        'command': 'move_to', 'data': {'x': 1, 'y': 2}}

    def test_each_fleet_uses_its_own_group_and_only_routes_its_commands(self):
        other_sent = []
        other = FleetCommandRouter('site-b', ['robot_2'], other_sent.append)
        self.assertNotEqual(self.router.consumer_group('bridge'), other.consumer_group('bridge'))
        for router in (self.router, other):
            router.dispatch(self.command)
        self.assertEqual([self.command], self.sent)
        self.assertEqual([], other_sent)

    def test_same_fleet_replicas_use_the_same_group_and_robot_set(self):
        replica = FleetCommandRouter('site-a', ['robot_1'], self.sent.append)
        self.assertEqual(self.router.consumer_group('bridge'), replica.consumer_group('bridge'))
        replica.dispatch(self.command)
        self.assertEqual([self.command], self.sent)

    def test_invalid_or_unowned_commands_are_rejected(self):
        cases = [[], None, {}, dict(self.command, robot_id='robot_2'),
                 dict(self.command, cmd_id='invalid'), dict(self.command, fleet_id=''),
                 dict(self.command, data=[]), dict(self.command, command='other')]
        for data in ({}, {'x': True, 'y': 1}, {'x': '1', 'y': 1},
                     {'x': float('nan'), 'y': 1}, {'x': float('inf'), 'y': 1},
                     {'x': 10 ** 1000, 'y': 1}):
            cases.append(dict(self.command, data=data))
        for command in cases:
            with self.subTest(command=command), self.assertRaises(CommandRejected):
                self.router.dispatch(command)
        self.assertEqual([], self.sent)

    def test_publish_failure_propagates_for_retry(self):
        def fail(command):
            raise RuntimeError('ROS unavailable')
        router = FleetCommandRouter('site-a', ['robot_1'], fail)
        with self.assertRaises(RuntimeError):
            router.dispatch(copy.deepcopy(self.command))

    def test_stop_does_not_require_coordinates(self):
        command = dict(self.command, command='stop', data={})
        self.router.dispatch(command)
        self.assertEqual([command], self.sent)

    def test_different_robot_sets_cannot_share_partitions(self):
        other = FleetCommandRouter('site-a', ['robot_2'], self.sent.append)
        self.assertNotEqual(self.router.consumer_group('bridge'), other.consumer_group('bridge'))
        first = FleetCommandRouter('site-a', ['robot_1', 'robot_2'], self.sent.append)
        reordered = FleetCommandRouter('site-a', ['robot_2', 'robot_1'], self.sent.append)
        self.assertEqual(first.consumer_group('bridge'), reordered.consumer_group('bridge'))

    def test_other_bridge_owned_commands_are_skipped_without_dlq_rejection(self):
        router = FleetCommandRouter('site-a', ['robot_2'], self.sent.append,
                                    fleet_robot_ids=['robot_1', 'robot_2'])
        router.dispatch(self.command)
        self.assertEqual([], self.sent)
        with self.assertRaises(CommandRejected):
            router.dispatch(dict(self.command, robot_id='robot_3'))

    def test_ownership_changes_use_fresh_generations_even_when_roster_reverts(self):
        groups = []
        for robots, generation in ((['robot_1'], '1'), (['robot_1', 'robot_2'], '2'), (['robot_1'], '3')):
            router = FleetCommandRouter('site-a', robots, self.sent.append,
                                        fleet_robot_ids=['robot_1', 'robot_2'],
                                        ownership_generation=generation)
            groups.append(router.consumer_group('bridge'))
        self.assertEqual(3, len(set(groups)))

    def test_noncanonical_uuid_spellings_are_rejected(self):
        canonical = 'abcdef01-2345-6789-abcd-ef0123456789'
        for spelling in (canonical.upper(), canonical.replace('-', ''), '{' + canonical + '}', 'urn:uuid:' + canonical):
            with self.subTest(spelling=spelling), self.assertRaises(CommandRejected):
                self.router.dispatch(dict(self.command, cmd_id=spelling))

    def test_local_ownership_must_be_in_fleet_manifest(self):
        with self.assertRaises(ValueError):
            FleetCommandRouter('site-a', ['robot_2'], self.sent.append, fleet_robot_ids=['robot_1'])
