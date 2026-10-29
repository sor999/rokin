from __future__ import annotations

from dataclasses import dataclass
import json
import math
import time

from .logic import FakeRobotLogic


@dataclass(frozen=True)
class CommandAck:
    """명령 처리 결과를 ROS 통신과 분리해 전달한다."""

    cmd_id: str
    status: str
    message: str


class RobotCommands:
    """이동·정지 명령의 수명주기와 완료 응답을 관리한다."""

    def __init__(self, logic: FakeRobotLogic, ttl_seconds=300.0, max_entries=10000, clock=time.monotonic):
        """로봇 동작과 현재 진행 중인 이동 명령을 연결한다."""
        if not math.isfinite(ttl_seconds) or ttl_seconds <= 0 or max_entries < 1:
            raise ValueError('Command cache limits must be positive and finite')
        self._ttl = ttl_seconds
        self._max_entries = max_entries
        self._clock = clock
        self._seen = {}
        self.logic = logic
        self.pending_move_id: str | None = None

    def handle(self, cmd_id: str, command: str, data: dict) -> list[CommandAck]:
        """명령을 적용하고 중단된 이동과 현재 명령의 응답을 반환한다."""
        if not isinstance(cmd_id, str) or not cmd_id or not isinstance(data, dict):
            raise ValueError('Command requires a nonempty cmd_id and object data')
        fingerprint = json.dumps([command, data], sort_keys=True, allow_nan=False)
        now = self._clock()
        self._seen = {key: value for key, value in self._seen.items()
                      if value[0] > now or key == self.pending_move_id}
        cached = self._seen.get(cmd_id)
        if cached is not None:
            if cached[1] != fingerprint:
                raise ValueError('cmd_id was reused for a different command')
            return [cached[2]]
        if len(self._seen) >= self._max_entries:
            return [CommandAck(cmd_id, 'failed', 'Command deduplication capacity exceeded')]
        acks = self._apply(cmd_id, command, data)
        for ack in acks:
            previous = self._seen.get(ack.cmd_id)
            signature = fingerprint if ack.cmd_id == cmd_id else previous[1] if previous else None
            if signature is not None:
                self._seen[ack.cmd_id] = (now + self._ttl, signature, ack)
        return acks

    def _apply(self, cmd_id, command, data):
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
            if not math.isfinite(x) or not math.isfinite(y):
                raise ValueError('move_to requires finite coordinates')
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
        ack = CommandAck(cmd_id, 'done', f'Arrived at ({x:.2f}, {y:.2f})')
        cached = self._seen.get(cmd_id)
        if cached is not None:
            self._seen[cmd_id] = (self._clock() + self._ttl, cached[1], ack)
        return ack
