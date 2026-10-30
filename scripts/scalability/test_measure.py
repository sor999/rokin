import http.server
import json
import threading
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from measure import Client, percentile, request, stomp_frames, summarize, timestamp
from lab_lock import lock


class MeasurementTest(unittest.TestCase):
    def setUp(self):
        self.profile = {'robots': 1, 'limits': {'min_event_source_ratio': .9, 'min_delivery_ratio': .99,
                        'min_acceptance_ratio': .99, 'event_p95_seconds': 2, 'ack_p95_seconds': 5}}

    def clients(self):
        """모든 이벤트와 종료 Ack를 받은 정상 연결 두 개를 만든다."""
        clients = [Client(i, 'http://localhost:18081', ['robot_1']) for i in range(2)]
        for client in clients:
            client.events = [(str(n), 100 + n / 6, 100 + n / 6 + .1) for n in range(60)]
            client.acks = {'cmd': [({'status': 'done'}, 104)]}
        clients[0].results = {'request': ({'cmdId': 'cmd'}, 103.1)}
        return clients

    def report(self, clients):
        return summarize(clients, [('request', 0, 103)], 100, 110, self.profile)

    def test_complete_delivery_passes(self):
        result = self.report(self.clients())
        self.assertTrue(result['passed'])
        self.assertEqual(result['ack_p95_seconds'], 1)

    def test_partitioned_delivery_is_detected(self):
        clients = self.clients()
        clients[0].events = clients[0].events[:30]
        clients[1].events = clients[1].events[30:]
        result = self.report(clients)
        self.assertTrue(result['checks']['event_source_rate'])
        self.assertFalse(result['checks']['event_delivery'])

    def test_missing_ack_is_not_hidden_by_fast_received_acks(self):
        clients = self.clients()
        clients[1].acks = {}
        result = self.report(clients)
        self.assertTrue(result['checks']['ack_latency'])
        self.assertFalse(result['checks']['terminal_ack_delivery'])

    def test_command_not_accepted_is_failure(self):
        clients = self.clients()
        clients[0].results = {}
        result = self.report(clients)
        self.assertFalse(result['checks']['command_acceptance'])
        self.assertFalse(result['checks']['terminal_ack_delivery'])

    def test_zero_events_is_failure(self):
        clients = self.clients()
        for client in clients:
            client.events = []
        self.assertFalse(self.report(clients)['checks']['event_source_rate'])

    def test_slow_event_and_disconnect_fail(self):
        clients = self.clients()
        clients[0].events = [(key, sent, received + 4) for key, sent, received in clients[0].events]
        clients[1].errors.append('SSE closed')
        result = self.report(clients)
        self.assertFalse(result['checks']['event_latency'])
        self.assertFalse(result['checks']['stream_connections'])

    def test_stomp_frames_with_heartbeat_and_multiple_messages(self):
        frames = list(stomp_frames('\nCONNECTED\nversion:1.2\n\n\0MESSAGE\ndestination:/topic/test\n\n{"x":1}\0'))
        self.assertEqual(frames[0][0], 'CONNECTED')
        self.assertEqual(frames[1][1]['destination'], '/topic/test')
        self.assertEqual(json.loads(frames[1][2]), {'x': 1})
        self.assertIsNone(percentile([], .95))

    def test_sockjs_send_accepts_empty_204_response(self):
        class Handler(http.server.BaseHTTPRequestHandler):
            def do_POST(self):
                self.rfile.read(int(self.headers['Content-Length']))
                self.send_response(204)
                self.end_headers()

            def log_message(self, *args):
                pass

        server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            self.assertIsNone(request(f'http://127.0.0.1:{server.server_port}/xhr_send', ['CONNECT\n\n\0']))
        finally:
            server.shutdown()
            server.server_close()
            thread.join()

    def test_java_timestamp_accepts_variable_fraction_precision(self):
        expected = timestamp('2026-10-30T00:36:23.877650Z')
        self.assertEqual(timestamp('2026-10-30T00:36:23.87765Z'), expected)
        self.assertEqual(timestamp('2026-10-30T00:36:23.877650123+00:00'), expected)

    def test_measurement_lock_rejects_concurrent_mutation(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'lab.lock'
            probe = 'from pathlib import Path; from lab_lock import lock; import sys\nwith lock(Path(sys.argv[1])): pass'
            env = {**os.environ, 'PYTHONPATH': str(Path(__file__).parent)}
            with lock(path):
                blocked = subprocess.run([sys.executable, '-c', probe, str(path)], env=env, capture_output=True)
                self.assertNotEqual(blocked.returncode, 0)
                self.assertIn('RuntimeError', blocked.stderr.decode())
            released = subprocess.run([sys.executable, '-c', probe, str(path)], env=env, capture_output=True)
            self.assertEqual(released.returncode, 0, released.stderr.decode())


if __name__ == '__main__':
    unittest.main()
