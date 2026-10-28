import hashlib
import json
import math
import re
import uuid


class CommandRejected(ValueError):
    pass


class FleetCommandRouter:
    def __init__(self, fleet_id, robot_ids, publish):
        if not isinstance(fleet_id, str) or not re.fullmatch(r'[A-Za-z0-9_-]{1,128}', fleet_id):
            raise ValueError('Invalid fleet_id')
        if not robot_ids or len(set(robot_ids)) != len(robot_ids):
            raise ValueError('robot_ids must be nonempty and unique')
        if any(not isinstance(robot, str) or not re.fullmatch(r'[A-Za-z0-9_-]{1,64}', robot) for robot in robot_ids):
            raise ValueError('Invalid robot_ids')
        self.fleet_id = fleet_id
        self.robot_ids = frozenset(robot_ids)
        self.publish = publish

    def consumer_group(self, prefix):
        if not isinstance(prefix, str) or not re.fullmatch(r'[A-Za-z0-9_-]{1,60}', prefix):
            raise ValueError('Invalid consumer group prefix')
        ownership = hashlib.sha256(json.dumps(sorted(self.robot_ids)).encode('utf-8')).hexdigest()
        return f'{prefix}.{self.fleet_id}.{ownership}'

    def dispatch(self, command):
        if not isinstance(command, dict):
            raise CommandRejected('Command must be a JSON object')
        fleet = command.get('fleet_id')
        if not isinstance(fleet, str) or not re.fullmatch(r'[A-Za-z0-9_-]{1,128}', fleet):
            raise CommandRejected('Missing or invalid fleet_id')
        if fleet != self.fleet_id:
            return
        cmd_id = command.get('cmd_id')
        try:
            uuid.UUID(cmd_id)
        except (ValueError, TypeError, AttributeError):
            raise CommandRejected('Invalid cmd_id') from None
        robot = command.get('robot_id')
        if not isinstance(robot, str) or robot not in self.robot_ids:
            raise CommandRejected('Robot is not owned by this fleet')
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
