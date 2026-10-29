import re
import math
import time


class SimulationReconciler:
    """ROS에 의존하지 않는 실행 목록 수렴과 중복 생성 방지를 담당한다."""

    def __init__(self, start, stop, ready, clock=time.monotonic, ready_timeout=30):
        self.start = start
        self.stop = stop
        self.ready = ready
        self.active = {}
        self.errors = {}
        self.started_at = {}
        self.clock = clock
        self.ready_timeout = ready_timeout

    @staticmethod
    def validate(snapshot):
        ids = set()
        for robot in snapshot:
            rid = robot.get('robotId')
            if (not isinstance(rid, str) or not re.fullmatch(r'[A-Za-z][A-Za-z0-9_]{0,63}', rid)
                    or rid in ids or type(robot.get('version')) is not int or robot['version'] < 1
                    or robot.get('desiredState') not in ('RUNNING', 'DELETED')):
                raise ValueError('Invalid simulation snapshot')
            ids.add(rid)
            for key in ('startX', 'startY', 'speed'):
                if type(robot.get(key)) not in (int, float) or not math.isfinite(robot[key]):
                    raise ValueError('Invalid simulation configuration')
            if not 0 < robot['speed'] <= 10:
                raise ValueError('Invalid simulation speed')

    def reconcile(self, snapshot):
        self.validate(snapshot)
        desired = {robot['robotId']: robot for robot in snapshot}
        for rid in list(self.active):
            robot = desired.get(rid)
            if robot is None or robot['desiredState'] == 'DELETED' or self.active[rid] != robot['version']:
                try:
                    self.stop(rid)
                    del self.active[rid]
                    self.started_at.pop(rid, None)
                    self.errors.pop(rid, None)
                except Exception:
                    self.errors[rid] = '로봇 종료에 실패했습니다.'
        reports = []
        for rid, robot in desired.items():
            state, error = 'STARTING', None
            if robot['desiredState'] == 'DELETED':
                state = 'FAILED' if rid in self.active else 'DELETED'
                error = self.errors.get(rid)
            else:
                if rid not in self.active:
                    try:
                        self.start(robot)
                        self.active[rid] = robot['version']
                        self.started_at[rid] = self.clock()
                        self.errors.pop(rid, None)
                    except Exception:
                        self.errors[rid] = '로봇 생성에 실패했습니다.'
                error = self.errors.get(rid)
                if error:
                    state = 'FAILED'
                elif self.ready(rid):
                    state = 'RUNNING'
                elif self.clock() - self.started_at[rid] >= self.ready_timeout:
                    state, error = 'FAILED', '명령 연결 준비 시간이 초과됐습니다. 자동으로 재확인합니다.'
            reports.append({'robotId': rid, 'version': robot['version'], 'state': state, 'error': error})
        return reports
