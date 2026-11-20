"""ROS에 의존하지 않는 실행 권한과 runtime별 통신 경로."""
import math
import re
import threading
import time


def runtime_clock():
    """Linux suspend와 프로세스 중단 시간을 포함하는 기한 시계다."""
    if not hasattr(time, 'CLOCK_BOOTTIME'):
        raise RuntimeError('Runtime leases require Linux CLOCK_BOOTTIME')
    return time.clock_gettime(time.CLOCK_BOOTTIME)


def topic_prefix(robot, runtime=None):
    """서로 다른 실행 세대의 DDS 메시지가 섞이지 않게 한다."""
    if runtime is None:
        return f'/fleet/{robot}'
    if not isinstance(runtime, str) or not re.fullmatch(r'[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}', runtime):
        raise ValueError('Invalid runtime namespace')
    return f'/fleet/runtime_{runtime.replace("-", "_")}/{robot}'


class RuntimeLease:
    """요청 시작 기준의 보수적인 로컬 임대 기한이며 만료 후 재사용하지 않는다."""

    def __init__(self, runtime, clock=runtime_clock, safety=2.0):
        self.runtime, self.clock, self.safety = runtime, clock, safety
        self.epoch = None
        self.deadline = 0.0
        self.retired = False
        self.lock = threading.RLock()

    def active(self):
        with self.lock:
            if self.epoch is not None and self.clock() >= self.deadline:
                self.retired = True
            return self.epoch is not None and not self.retired

    def revoke(self):
        with self.lock:
            self.retired = True

    def accept(self, response, started, fleet):
        with self.lock:
            if not isinstance(response,dict) or type(response.get('granted')) is not bool or type(response.get('retired')) is not bool:
                raise ValueError('Invalid lease decision')
            if response.get('fleetId') != fleet or response.get('runtimeId') != self.runtime:
                raise ValueError('Lease ownership mismatch')
            if response.get('retired'):
                self.retired = True
            if not response.get('granted'):
                if self.epoch is not None:
                    self.retired=True
                return False
            epoch, ttl = response.get('epoch'), response.get('ttlMillis')
            if (type(epoch) is not int or epoch < 1 or type(ttl) is not int or not 6000 <= ttl <= 30000
                    or not math.isfinite(started) or started > self.clock()):
                raise ValueError('Invalid lease grant')
            # 늦은 갱신 응답으로 이미 만료된 프로세스를 되살리지 않는다.
            self.active()
            deadline = started + ttl / 1000 - self.safety
            if self.retired or deadline <= self.clock() or (self.epoch is not None and self.epoch != epoch):
                self.retired = True
                return False
            self.epoch, self.deadline = epoch, deadline
            return True
