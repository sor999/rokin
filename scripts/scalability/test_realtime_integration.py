import os
import subprocess
import time
import unittest
import urllib.error

from lab_lock import lock
from measure import Client, prepare, request, verify_lab


@unittest.skipUnless(os.environ.get('RUN_REALTIME_INTEGRATION') == '1', 'requires the isolated realtime lab')
class RealtimeIntegrationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.guard = lock()
        cls.guard.__enter__()
        try:
            verify_lab('dual')
            cls.robots = prepare(3)
        except Exception:
            cls.guard.__exit__(None, None, None)
            raise

    @classmethod
    def tearDownClass(cls):
        cls.guard.__exit__(None, None, None)

    def setUp(self):
        self.clients = []

    def tearDown(self):
        for client in self.clients:
            client.stop.set()

    def wait(self, predicate, seconds=30):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            if predicate():
                return
            time.sleep(.1)
        self.fail('Timed out waiting for realtime convergence')

    def healthy(self, port):
        try:
            return request(f'http://localhost:{port}/actuator/health')['status'] == 'UP'
        except Exception:
            return False

    def connect(self, port):
        client = Client(len(self.clients), f'http://localhost:{port}', self.robots)
        self.clients.append(client)
        client.start()
        self.wait(lambda: client.sse_ready.is_set() and client.ws_ready.is_set())
        self.assertFalse(client.errors, client.errors)
        return client

    def stop_command(self, client):
        import json
        import uuid
        rid = uuid.uuid4().hex
        client.send('SEND', {'destination': '/app/command', 'content-type': 'application/json'},
                    json.dumps({'requestId': rid, 'robotId': 'robot_1', 'command': 'stop', 'data': {}}))
        self.wait(lambda: rid in client.results, 5)
        return client.results[rid][0]['cmdId']

    def ack_done(self, client, cmd):
        return any(value['status'] == 'done' for value, _ in client.acks.get(cmd, []))

    def persisted(self, port, cmd):
        try:
            return request(f'http://localhost:{port}/api/robots/robot_1/acks/{cmd}')['status'] == 'done'
        except urllib.error.HTTPError as error:
            if error.code == 404:
                return False
            raise

    def docker(self, *args):
        subprocess.run(['docker', *args], check=True, stdout=subprocess.DEVNULL, timeout=40)

    def test_redis_disconnect_closes_clients_and_rest_recovers_known_ack(self):
        first, second = self.connect(18081), self.connect(18082)
        cmd = self.stop_command(first)
        self.wait(lambda: self.ack_done(first, cmd) and self.ack_done(second, cmd), 5)
        try:
            self.docker('stop', '--time', '2', 'rokin-scale-lab-redis-1')
            self.wait(lambda: not self.healthy(18081) and not self.healthy(18082), 8)
            self.wait(lambda: all(any(error.startswith("SSE") for error in client.errors)
                                 and any(error.startswith("SockJS") for error in client.errors)
                                 for client in (first, second)), 8)
            self.wait(lambda: self.persisted(18081, cmd) and self.persisted(18082, cmd), 8)
            with self.assertRaises(urllib.error.HTTPError) as error:
                request('http://localhost:18081/api/stream/telemetry')
            self.assertEqual(error.exception.code, 503)
        finally:
            self.docker('start', 'rokin-scale-lab-redis-1')
        started = time.monotonic()
        self.wait(lambda: self.healthy(18081) and self.healthy(18082))
        fresh_first, fresh_second = self.connect(18081), self.connect(18082)
        self.wait(lambda: bool(fresh_first.events) and bool(fresh_second.events))
        self.assertLess(time.monotonic() - started, 30)
        next_cmd = self.stop_command(fresh_second)
        self.wait(lambda: self.ack_done(fresh_first, next_cmd) and self.ack_done(fresh_second, next_cmd), 5)
        self.assertTrue(self.persisted(18081, cmd))

    def test_api_restart_preserves_other_node_and_rest_state(self):
        first, second = self.connect(18081), self.connect(18082)
        cmd = self.stop_command(first)
        self.wait(lambda: self.ack_done(first, cmd) and self.ack_done(second, cmd), 5)
        self.wait(lambda: self.persisted(18082, cmd), 5)
        self.wait(lambda: {key for key, _, _ in first.events} & {key for key, _, _ in second.events}, 15)
        try:
            self.docker('stop', '--time', '20', 'rokin-scale-lab-api-1')
            self.assertTrue(self.healthy(18082))
            count = len(second.events)
            self.wait(lambda: len(second.events) > count, 15)
            self.assertTrue(self.persisted(18082, cmd))
        finally:
            self.docker('start', 'rokin-scale-lab-api-1')
        started = time.monotonic()
        self.wait(lambda: self.healthy(18081))
        fresh = self.connect(18081)
        self.wait(lambda: bool(fresh.events))
        self.assertLess(time.monotonic() - started, 30)
        self.assertTrue(self.persisted(18081, cmd))
        next_cmd = self.stop_command(second)
        self.wait(lambda: self.ack_done(fresh, next_cmd) and self.ack_done(second, next_cmd), 5)

    def test_shared_presence_database_outage_and_recovery(self):
        first, second = self.connect(18081), self.connect(18082)

        def online(port):
            try:
                robots = [robot for robot in request(f'http://localhost:{port}/api/robots')
                          if robot['robotId'] in self.robots]
                return len(robots) == len(self.robots) and all(robot['online'] for robot in robots)
            except Exception:
                return False

        self.wait(lambda: online(18081) and online(18082))
        for client in (first, second):
            cmd = self.stop_command(client)
            self.wait(lambda: self.ack_done(first, cmd) and self.ack_done(second, cmd), 5)
        try:
            self.docker('stop', '--time', '2', 'rokin-scale-lab-postgres-1')
            for port in (18081, 18082):
                with self.assertRaises(urllib.error.HTTPError) as error:
                    request(f'http://localhost:{port}/api/robots')
                self.assertEqual(error.exception.code, 503)
            self.wait(lambda: all(any(error.startswith('SSE') for error in client.errors)
                                 for client in (first, second)), 15)
        finally:
            self.docker('start', 'rokin-scale-lab-postgres-1')
        self.wait(lambda: self.healthy(18081) and self.healthy(18082), 30)
        self.wait(lambda: online(18081) and online(18082), 30)
        fresh_first, fresh_second = self.connect(18081), self.connect(18082)
        for client in (fresh_first, fresh_second):
            cmd = self.stop_command(client)
            self.wait(lambda: self.ack_done(fresh_first, cmd) and self.ack_done(fresh_second, cmd), 5)

    def test_runtime_restart_converges_both_api_presence(self):
        first, second = self.connect(18081), self.connect(18082)
        previous = {robot['runtimeId'] for robot in request('http://localhost:18081/api/simulations')
                    if robot['robotId'] in self.robots}
        self.docker('restart', '--time', '10', 'rokin-scale-lab-fake_robot-1')

        def restored():
            for port in (18081, 18082):
                registrations = [robot for robot in request(f'http://localhost:{port}/api/simulations')
                                 if robot['robotId'] in self.robots]
                if not registrations or any(robot['runtimeId'] in previous or robot['state'] != 'RUNNING'
                                            for robot in registrations):
                    return False
                robots = [robot for robot in request(f'http://localhost:{port}/api/robots')
                          if robot['robotId'] in self.robots]
                if len(robots) != len(self.robots) or not all(robot['online'] for robot in robots):
                    return False
            return True

        started = time.monotonic()
        self.wait(restored, 60)
        print(f"ROS runtime recovery: {time.monotonic() - started:.3f}s", flush=True)
        for client in (first, second):
            cmd = self.stop_command(client)
            self.wait(lambda: self.ack_done(first, cmd) and self.ack_done(second, cmd), 5)
