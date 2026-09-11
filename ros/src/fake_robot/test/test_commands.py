import unittest

from fake_robot.commands import RobotCommands
from fake_robot.logic import FakeRobotLogic


class RobotStopTest(unittest.TestCase):
    """정지 후 좌표와 명령 수명주기가 유지되는지 검증한다."""

    def setUp(self):
        """배터리 소모가 없는 로봇으로 명령 동작을 검증한다."""
        self.logic = FakeRobotLogic(battery_drain_rate=0)
        self.commands = RobotCommands(self.logic)

    def test_stop_during_movement_keeps_position_and_ends_both_commands(self):
        """이동 중 정지는 좌표를 고정하고 중단·정지 응답을 구분한다."""
        self.commands.handle('move-1', 'move_to', {'x': 100, 'y': 0})
        self.logic.update(2)
        position = self.logic.get_pose()

        acks = self.commands.handle('stop-1', 'stop', {})
        for _ in range(20):
            self.logic.update(0.5)

        self.assertEqual(position, self.logic.get_pose())
        self.assertEqual('idle', self.logic.get_status())
        self.assertEqual([('move-1', 'failed'), ('stop-1', 'done')],
                         [(ack.cmd_id, ack.status) for ack in acks])
        self.assertIsNone(self.commands.pending_move_id)
        self.assertIsNone(self.commands.complete_move())

    def test_repeated_stop_does_not_resume_movement(self):
        """같은 정지 명령을 다시 받아도 이동을 재개하지 않는다."""
        self.commands.handle('move-1', 'move_to', {'x': 100, 'y': 0})
        self.logic.update(2)
        self.commands.handle('stop-1', 'stop', {})
        position = self.logic.get_pose()

        acks = self.commands.handle('stop-1', 'stop', {})
        self.logic.update(5)

        self.assertEqual(position, self.logic.get_pose())
        self.assertEqual([('stop-1', 'done')], [(a.cmd_id, a.status) for a in acks])

    def test_stop_while_idle_completes_without_coordinates(self):
        """좌표 입력 없이 대기 중인 로봇에 정지 명령을 보낼 수 있다."""
        acks = self.commands.handle('stop-1', 'stop', {})

        self.assertEqual('done', acks[0].status)
        self.assertEqual((0, 0), self.logic.get_pose())

    def test_new_move_after_stop_only_completes_new_command(self):
        """정지 후 이동을 재개해도 취소한 이동의 완료 응답이 나오지 않는다."""
        self.commands.handle('move-1', 'move_to', {'x': 100, 'y': 0})
        self.commands.handle('stop-1', 'stop', {})
        self.commands.handle('move-2', 'move_to', {'x': 1, 'y': 0})
        self.logic.update(1)

        ack = self.commands.complete_move()

        self.assertEqual(('move-2', 'done'), (ack.cmd_id, ack.status))
        self.assertIsNone(self.commands.complete_move())

    def test_unknown_command_does_not_stop_movement(self):
        """지원하지 않는 명령은 실패하며 현재 이동을 변경하지 않는다."""
        self.commands.handle('move-1', 'move_to', {'x': 100, 'y': 0})

        acks = self.commands.handle('invalid-1', 'invalid', {})

        self.assertEqual('failed', acks[0].status)
        self.assertEqual('moving', self.logic.get_status())
