import unittest

from fake_robot.commands import RobotCommands
from fake_robot.logic import FakeRobotLogic


class CommandDedupTest(unittest.TestCase):
    def setUp(self):
        self.now = 0.0
        self.logic = FakeRobotLogic(battery_drain_rate=0)
        self.commands = RobotCommands(self.logic, ttl_seconds=10, max_entries=4, clock=lambda: self.now)

    def test_replayed_move_after_stop_does_not_restart_movement(self):
        self.commands.handle('move-1', 'move_to', {'x': 100, 'y': 0})
        self.logic.update(1)
        self.commands.handle('stop-1', 'stop', {})
        position = self.logic.get_pose()
        ack = self.commands.handle('move-1', 'move_to', {'x': 100, 'y': 0})[0]
        self.logic.update(1)
        self.assertEqual('failed', ack.status)
        self.assertEqual(position, self.logic.get_pose())
        self.assertIsNone(self.commands.pending_move_id)

    def test_replayed_stop_does_not_interrupt_a_new_move(self):
        self.commands.handle('stop-1', 'stop', {})
        self.commands.handle('move-1', 'move_to', {'x': 100, 'y': 0})
        self.commands.handle('stop-1', 'stop', {})
        self.assertEqual('moving', self.logic.get_status())
        self.assertEqual('move-1', self.commands.pending_move_id)

    def test_completed_move_replays_terminal_ack(self):
        self.commands.handle('move-1', 'move_to', {'x': 1, 'y': 0})
        self.logic.update(1)
        completed = self.commands.complete_move()
        replay = self.commands.handle('move-1', 'move_to', {'x': 1, 'y': 0})
        self.assertEqual([completed], replay)
        self.assertIsNone(self.commands.pending_move_id)

    def test_active_move_is_protected_after_ttl(self):
        self.commands.handle('move-1', 'move_to', {'x': 100, 'y': 0})
        self.now = 20
        self.commands.handle('move-1', 'move_to', {'x': 100, 'y': 0})
        self.assertEqual('move-1', self.commands.pending_move_id)
        self.assertEqual('moving', self.logic.get_status())

    def test_expired_completed_command_can_be_reused(self):
        self.commands.handle('stop-1', 'stop', {})
        self.now = 11
        ack = self.commands.handle('stop-1', 'move_to', {'x': 100, 'y': 0})[0]
        self.assertEqual('accepted', ack.status)

    def test_capacity_rejects_new_commands_without_evicting_live_keys(self):
        commands = RobotCommands(self.logic, ttl_seconds=10, max_entries=1, clock=lambda: self.now)
        commands.handle('stop-1', 'stop', {})
        ack = commands.handle('move-1', 'move_to', {'x': 100, 'y': 0})[0]
        self.assertEqual('failed', ack.status)
        self.assertEqual('idle', self.logic.get_status())
        self.now = 11
        self.assertEqual('accepted', commands.handle('move-1', 'move_to', {'x': 100, 'y': 0})[0].status)

    def test_same_id_with_changed_payload_is_rejected(self):
        self.commands.handle('stop-1', 'stop', {})
        with self.assertRaises(ValueError):
            self.commands.handle('stop-1', 'move_to', {'x': 100, 'y': 0})
        self.assertEqual('idle', self.logic.get_status())

    def test_invalid_limits_and_nonfinite_coordinates_are_rejected(self):
        for ttl, maximum in ((0, 1), (float('nan'), 1), (10, 0)):
            with self.assertRaises(ValueError):
                RobotCommands(self.logic, ttl_seconds=ttl, max_entries=maximum)
        with self.assertRaises(ValueError):
            self.commands.handle('move-1', 'move_to', {'x': float('inf'), 'y': 0})
        self.assertEqual('idle', self.logic.get_status())
