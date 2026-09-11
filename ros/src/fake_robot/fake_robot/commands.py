from __future__ import annotations

from dataclasses import dataclass

from .logic import FakeRobotLogic


@dataclass(frozen=True)
class CommandAck:
    """명령 처리 결과를 ROS 통신과 분리해 전달한다."""

    cmd_id: str
    status: str
    message: str


class RobotCommands:
    """이동·정지 명령의 수명주기와 완료 응답을 관리한다."""

    def __init__(self, logic: FakeRobotLogic):
        """로봇 동작과 현재 진행 중인 이동 명령을 연결한다."""
        self.logic = logic
        self.pending_move_id: str | None = None

    def handle(self, cmd_id: str, command: str, data: dict) -> list[CommandAck]:
        """명령을 적용하고 중단된 이동과 현재 명령의 응답을 반환한다."""
        if command == 'stop':
            self.logic.stop()
            interrupted_id = self.pending_move_id
            self.pending_move_id = None
            acks = []
            if interrupted_id is not None:
                acks.append(CommandAck(interrupted_id, 'failed', 'Movement interrupted by stop'))
            acks.append(CommandAck(cmd_id, 'done', 'Robot stopped'))
            return acks

        if command == 'move_to':
            x = float(data.get('x', self.logic.x))
            y = float(data.get('y', self.logic.y))
            self.logic.set_target(x, y)
            self.pending_move_id = cmd_id
            return [CommandAck(cmd_id, 'accepted', f'Moving to ({x}, {y})')]

        return [CommandAck(cmd_id, 'failed', f'Unknown command: {command}')]

    def complete_move(self) -> CommandAck | None:
        """도착한 이동 명령에 한 번만 완료 응답을 생성한다."""
        if self.pending_move_id is None or self.logic.get_status() != 'arrived':
            return None
        cmd_id = self.pending_move_id
        self.pending_move_id = None
        x, y = self.logic.get_pose()
        return CommandAck(cmd_id, 'done', f'Arrived at ({x:.2f}, {y:.2f})')
