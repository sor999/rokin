import hashlib
import json
import math
import re
import uuid


class CommandRejected(ValueError):
    """DLQ로 격리해야 하는 잘못된 명령을 나타낸다."""


class FleetCommandRouter:
    """Fleet 전체 명세와 담당 로봇 집합을 기준으로 명령을 검증하고 전달한다."""

    def __init__(self, fleet_id, robot_ids, publish, fleet_robot_ids=None, ownership_generation="1"):
        """Fleet 명세의 부분집합을 담당하며 소유권 변경마다 새 세대를 사용한다."""
        if not isinstance(fleet_id, str) or not re.fullmatch(r'[A-Za-z0-9_-]{1,128}', fleet_id):
            raise ValueError('Invalid fleet_id')
        if not robot_ids or len(set(robot_ids)) != len(robot_ids):
            raise ValueError('robot_ids must be nonempty and unique')
        if any(not isinstance(robot, str) or not re.fullmatch(r'[A-Za-z0-9_-]{1,64}', robot) for robot in robot_ids):
            raise ValueError('Invalid robot_ids')
        fleet_robot_ids = robot_ids if fleet_robot_ids is None else fleet_robot_ids
        if (not fleet_robot_ids or len(set(fleet_robot_ids)) != len(fleet_robot_ids)
                or any(not isinstance(robot, str) or not re.fullmatch(r'[A-Za-z0-9_-]{1,64}', robot)
                       for robot in fleet_robot_ids)):
            raise ValueError('Invalid fleet_robot_ids')
        if not set(robot_ids).issubset(fleet_robot_ids):
            raise ValueError('Local robot_ids must belong to fleet_robot_ids')
        if not isinstance(ownership_generation, str) or not re.fullmatch(r'[A-Za-z0-9_-]{1,32}', ownership_generation):
            raise ValueError('Invalid ownership_generation')
        self.ownership_generation = ownership_generation
        self.fleet_robot_ids = frozenset(fleet_robot_ids)
        self.fleet_id = fleet_id
        self.robot_ids = frozenset(robot_ids)
        self.publish = publish

    def consumer_group(self, prefix):
        """일반 재시작에는 유지하고 소유권 변경에는 새 세대를 쓰는 group ID를 반환한다."""
        if not isinstance(prefix, str) or not re.fullmatch(r'[A-Za-z0-9_-]{1,60}', prefix):
            raise ValueError('Invalid consumer group prefix')
        ownership = hashlib.sha256(json.dumps(
            [sorted(self.fleet_robot_ids), sorted(self.robot_ids)]
        ).encode('utf-8')).hexdigest()
        group = f'{prefix}.{self.fleet_id}.{self.ownership_generation}.{ownership}'
        if len(group) > 255:
            raise ValueError('Consumer group is too long')
        return group

    def dispatch(self, command):
        """다른 담당의 명령은 건너뛰고, 담당 명령은 검증한 뒤 publish에 전달한다.

        다른 Fleet이나 같은 Fleet의 다른 Bridge 담당 명령은 정상 반환한다.
        잘못된 명령은 CommandRejected로 DLQ 처리를 요청한다.
        publish 오류는 전파해 원본 offset을 커밋하지 않고 재시도하게 한다.
        """
        if not isinstance(command, dict):
            raise CommandRejected('Command must be a JSON object')
        fleet = command.get('fleet_id')
        if not isinstance(fleet, str) or not re.fullmatch(r'[A-Za-z0-9_-]{1,128}', fleet):
            raise CommandRejected('Missing or invalid fleet_id')
        if fleet != self.fleet_id:
            return
        robot = command.get('robot_id')
        if not isinstance(robot, str) or robot not in self.fleet_robot_ids:
            raise CommandRejected('Robot is not owned by this fleet')
        if robot not in self.robot_ids:
            return
        cmd_id = command.get('cmd_id')
        try:
            canonical = str(uuid.UUID(cmd_id))
        except (ValueError, TypeError, AttributeError):
            raise CommandRejected('Invalid cmd_id') from None
        if canonical != cmd_id:
            raise CommandRejected('cmd_id must be a canonical lowercase UUID')
        action = command.get('command')
        data = command.get('data')
        if action not in ('stop', 'move_to') or not isinstance(data, dict):
            raise CommandRejected('Invalid command or data')
        if action == 'move_to':
            for axis in ('x', 'y'):
                value = data.get(axis)
                try:
                    valid = not isinstance(value, bool) and isinstance(value, (int, float)) and math.isfinite(value)
                except OverflowError:
                    valid = False
                if not valid:
                    raise CommandRejected('move_to requires finite numeric x and y')
        self.publish(command)
