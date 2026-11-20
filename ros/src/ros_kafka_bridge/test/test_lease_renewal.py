from pathlib import Path
import sys
import threading
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'fake_robot'))
from fake_robot.runtime_lease import RuntimeLease
from ros_kafka_bridge.lease_renewal import BudgetedRequest, LeaseRenewer

RUNTIME = '00000000-0000-4000-8000-000000000001'


class SimulatedStop:
    def __init__(self, now, iterations):
        self.now = now
        self.iterations = iterations
        self.waits = []

    def is_set(self):
        return len(self.waits) >= self.iterations

    def wait(self, delay):
        self.waits.append(delay)
        self.now[0] += delay


class LeaseRenewalTest(unittest.TestCase):
    def setUp(self):
        self.now = [0.0]
        self.lease = RuntimeLease(RUNTIME, clock=lambda: self.now[0])
        self.response = dict(fleetId='default', runtimeId=RUNTIME, epoch=1,
                             granted=True, retired=False, ttlMillis=6000)

    def renewer(self, request, stop, **options):
        return LeaseRenewer(self.lease, request, stop, 'default', RUNTIME, **options)

    def test_slow_responses_preserve_minimum_ttl_lease(self):
        starts = []

        def request():
            starts.append(self.now[0])
            self.now[0] += 1.4
            return self.response

        stop = SimulatedStop(self.now, 6)
        self.renewer(request, stop).run()
        self.assertEqual([0.0] * 6, stop.waits)
        self.assertEqual(6, len(starts))
        self.assertTrue(self.lease.active())
        self.assertAlmostEqual(starts[-1] + 4, self.lease.deadline)

    def test_fast_responses_wait_only_remaining_period(self):
        starts = []

        def request():
            starts.append(self.now[0])
            self.now[0] += 0.25
            return self.response

        stop = SimulatedStop(self.now, 3)
        self.renewer(request, stop).run()
        self.assertEqual([0, 1, 2], starts)
        self.assertEqual([0.75] * 3, stop.waits)

    def test_unsafe_period_budget_ttl_combinations_rejected(self):
        for options in [dict(period=2, budget=2), dict(budget=2), dict(period=0),
                        dict(budget=float('nan')), dict(period=float('inf')),
                        dict(minimum_ttl_millis=5000)]:
            with self.subTest(options=options), self.assertRaises(ValueError):
                self.renewer(lambda: self.response, SimulatedStop(self.now, 1), **options)

    def test_over_budget_response_does_not_extend_deadline(self):
        self.assertTrue(self.lease.accept(self.response, 0, 'default'))

        def request():
            self.now[0] += 1.5
            return self.response

        stop = SimulatedStop(self.now, 3)
        with self.assertLogs('ros_kafka_bridge.lease_renewal', level='WARNING'):
            self.renewer(request, stop).run()
        self.assertEqual(4, self.lease.deadline)
        self.assertFalse(self.lease.active())
        self.assertTrue(self.lease.retired)

    def test_response_after_existing_deadline_cannot_revive_runtime(self):
        self.lease.accept(self.response, 0, 'default')
        self.now[0] = 3.5

        def request():
            self.now[0] += 1
            return self.response

        self.renewer(request, SimulatedStop(self.now, 1)).run()
        self.assertTrue(self.lease.retired)
        self.assertEqual(4, self.lease.deadline)

    def test_blocked_request_times_out_without_spawning_more_workers(self):
        entered = threading.Event()
        release = threading.Event()
        finished = threading.Event()
        bounded = BudgetedRequest()

        def request():
            entered.set()
            try:
                release.wait()
                return self.response
            finally:
                finished.set()

        try:
            with self.assertRaises(TimeoutError):
                bounded(request, 0.05)
            self.assertTrue(entered.is_set())
            for _ in range(3):
                with self.assertRaises(TimeoutError):
                    bounded(lambda: self.fail('overlapping request started'), 0.05)
        finally:
            release.set()
            self.assertTrue(finished.wait(1))

    def test_request_errors_propagate_and_next_request_can_succeed(self):
        bounded = BudgetedRequest()

        def failing_request():
            raise OSError('unavailable')

        with self.assertRaises(OSError):
            bounded(failing_request, 1)
        self.assertEqual(self.response, bounded(lambda: self.response, 1))
