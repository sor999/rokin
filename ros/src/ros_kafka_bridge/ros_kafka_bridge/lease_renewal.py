import logging
import math
import queue
import threading

logger = logging.getLogger(__name__)


class BudgetedRequest:
    def __init__(self):
        self.in_flight = threading.Lock()

    def __call__(self, request, budget):
        if not self.in_flight.acquire(blocking=False):
            raise TimeoutError('Previous lease request is still in flight')
        result = queue.Queue(maxsize=1)

        def run():
            try:
                outcome = (True, request())
            except Exception as error:
                outcome = (False, error)
            finally:
                self.in_flight.release()
            result.put(outcome)

        worker = threading.Thread(target=run, name='runtime-lease-request', daemon=True)
        try:
            worker.start()
        except Exception:
            self.in_flight.release()
            raise
        try:
            succeeded, value = result.get(timeout=budget)
        except queue.Empty:
            raise TimeoutError('Lease request exceeded total time budget') from None
        if not succeeded:
            raise value
        return value


class LeaseRenewer:
    def __init__(self, lease, request, stop_event, fleet_id, runtime_id,
                 period=1.0, budget=1.5, minimum_ttl_millis=6000, accept=None):
        if (not all(math.isfinite(value) and value > 0 for value in (period, budget))
                or max(period, budget) + budget >= minimum_ttl_millis / 1000 - lease.safety):
            raise ValueError('Unsafe lease renewal period, request budget and TTL')
        self.lease = lease
        self.accept = accept if accept is not None else lease.accept
        self.request = request
        self.stop_event = stop_event
        self.fleet_id = fleet_id
        self.runtime_id = runtime_id
        self.period = period
        self.budget = budget
        self.bounded_request = BudgetedRequest()
        self.acquisition_failure_window = minimum_ttl_millis / 1000

    def run(self):
        failure_deadline = None
        while not self.stop_event.is_set():
            if self.lease.retired or (self.lease.epoch is not None and not self.lease.active()):
                return
            started = self.lease.clock()
            if failure_deadline is not None and started >= failure_deadline:
                self.lease.revoke()
                return
            try:
                response = self.bounded_request(self.request, self.budget)
                if self.lease.clock() - started >= self.budget:
                    raise TimeoutError('Lease response arrived after total time budget')
                if failure_deadline is not None and self.lease.clock() >= failure_deadline:
                    self.lease.revoke()
                    return
                previous = self.lease.epoch
                granted = self.accept(response, started, self.fleet_id)
                failure_deadline = None
                if granted and previous is None:
                    logger.info('Runtime lease acquired runtime=%s epoch=%s', self.runtime_id, self.lease.epoch)
            except Exception:
                if self.lease.epoch is None:
                    if failure_deadline is None:
                        failure_deadline = started + self.acquisition_failure_window
                    if self.lease.clock() >= failure_deadline:
                        self.lease.revoke()
                        return
                logger.warning('Runtime lease renewal failed; keeping only existing local deadline')
            self.stop_event.wait(max(0.0, self.period - (self.lease.clock() - started)))
