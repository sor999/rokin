"""Bridge journal reconciliation; HTTP work never changes ROS objects."""
import datetime
import json
import os
import threading
from urllib.request import Request, urlopen


class DurableBridgeDelivery:
    def __init__(self, journal, producer, router, publishers, logger, lease=None):
        self.lease=lease
        self.journal, self.producer, self.router, self.publishers, self.logger = journal, producer, router, publishers, logger
        self.api = os.environ.get('SIMULATOR_API_URL', 'http://api:8080').rstrip('/')
        self.token = os.environ.get('SIMULATOR_INTERNAL_TOKEN', '')
        if not self.token:
            raise ValueError('SIMULATOR_INTERNAL_TOKEN required for durable delivery')
        self.stopping = threading.Event()
        self.storage_error = False
        self.storage_failures = 0
        self.retransmissions = 0
        self.thread = threading.Thread(target=self.sync, name='durable-delivery', daemon=True)

    def start(self):
        self.thread.start()

    def stop(self):
        self.stopping.set()
        self.thread.join(timeout=15)
        if self.thread.is_alive():
            raise RuntimeError('Durable delivery sync did not stop')

    def request(self, path, body):
        request = Request(self.api + '/internal/commands/' + path, data=json.dumps(body).encode(), headers={
            'X-Simulator-Token': self.token, 'Content-Type': 'application/json'})
        with urlopen(request, timeout=3) as response:
            payload = response.read()
            return json.loads(payload) if payload else None

    def healthy(self):
        try:
            return not self.storage_error and self.journal.healthy()
        except Exception:
            return False

    def relay(self):
        # This method is invoked only by the ROS executor, never by the HTTP thread.
        from std_msgs.msg import String
        if self.lease and not self.lease.active():
            return
        try:
            for command in self.journal.pending():
                robot = command['robot_id']
                if hasattr(self.router, 'owns_command') and not self.router.owns_command(command):
                    self.journal.reject_inbox(command['cmd_id'])
                    continue
                if getattr(self.router, 'runtime_id', None):
                    if (command.get('simulation_runtime_id') != self.router.runtime_id or
                            command.get('simulation_version') != self.router.versions.get(robot)):
                        self.journal.reject_inbox(command['cmd_id'])
                        continue
                publisher = self.publishers.get(robot)
                if publisher is None:
                    # Removed robots and old runtime journals remain recoverable, never rerouted.
                    self.journal.reject_inbox(command['cmd_id'])
                    continue
                msg = String()
                msg.data = json.dumps(command)
                if self.lease and not self.lease.active():
                    return
                publisher.publish(msg)
        except Exception:
            self.storage_error = True
            self.storage_failures += 1
            self.logger.error('Durable relay failed; command readiness disabled')

    def sync(self):
        while not self.stopping.is_set():
            try:
                receipts = self.journal.receipts()
                if receipts:
                    self.request('received', receipts)
                    self.journal.reported(receipts)
                acks = self.journal.acks()
                for ack in acks:
                    self.producer.produce('ack.robot', key=ack['robot_id'], value=ack)
                    self.retransmissions += 1
                if acks:
                    confirmed = self.request('ack-receipts', [ack['event_id'] for ack in acks])
                    allowed = {ack['event_id'] for ack in acks}
                    self.journal.confirmed([event for event in confirmed['eventIds'] if event in allowed])
                self.request('journal', {**self.journal.stats(), 'storageFailures': self.storage_failures,
                                         'retransmissions': self.retransmissions})
                self.storage_error = False
            except Exception:
                self.logger.warning('Durable delivery sync failed; retaining local records')
            self.stopping.wait(2)
