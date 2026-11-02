import http.server
import json
import subprocess
import tempfile
import threading
import time
import unittest
from pathlib import Path

from compare import validate_pair
from measure import Client, stomp_frames, targets_healthy
from provenance import source_fingerprint, validate_output


class ComparisonTest(unittest.TestCase):
    def pair(self):
        base = {'profile': {}, 'git_revision': 'revision', 'working_tree': [], 'source_fingerprint': 'hash',
                'docker_host': {'cpus': 4, 'memory_bytes': 8192, 'architecture': 'arm64', 'version': '29'}}
        return {**base, 'mode': 'single'}, {**base, 'mode': 'dual'}

    def test_same_source_is_comparable(self):
        validate_pair(*self.pair())

    def test_different_working_tree_is_rejected(self):
        single, dual = self.pair()
        dual['working_tree'] = [' M source.py']
        with self.assertRaisesRegex(ValueError, 'working_tree'):
            validate_pair(single, dual)

    def test_same_status_but_different_content_is_rejected(self):
        single, dual = self.pair()
        single['working_tree'] = dual['working_tree'] = [' M source.py']
        dual['source_fingerprint'] = 'different'
        with self.assertRaisesRegex(ValueError, '소스 내용'):
            validate_pair(single, dual)

    def test_legacy_reports_are_not_assumed_identical(self):
        single, dual = self.pair()
        del single['source_fingerprint']
        del dual['source_fingerprint']
        with self.assertRaisesRegex(ValueError, '이전 결과'):
            validate_pair(single, dual)

    def test_source_hash_detects_untracked_edits_and_ignores_results(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            subprocess.run(['git', 'init', '-q', directory], check=True)
            (root / '.gitignore').write_text('tmp/\n')
            source = root / 'source.py'
            source.write_text('first')
            first = source_fingerprint(root)
            (root / 'tmp').mkdir()
            (root / 'tmp/result.json').write_text('result')
            self.assertEqual(source_fingerprint(root), first)
            source.write_text('second')
            self.assertNotEqual(source_fingerprint(root), first)

    def test_missing_or_different_docker_specs_are_rejected(self):
        for key in ('cpus', 'memory_bytes', 'architecture', 'version'):
            with self.subTest(key=key):
                single, dual = self.pair()
                value = single['docker_host'][key]
                dual['docker_host'] = {**dual['docker_host'], key: value + 1 if type(value) is int else value + '-other'}
                with self.assertRaisesRegex(ValueError, key):
                    validate_pair(single, dual)
                del dual['docker_host'][key]
                with self.assertRaisesRegex(ValueError, key):
                    validate_pair(single, dual)

    def test_output_cannot_pollute_repository_or_overwrite_tracked_files(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory) / 'repo'
            root.mkdir()
            subprocess.run(['git', 'init', '-q', str(root)], check=True)
            (root / '.gitignore').write_text('tmp/\n')
            with self.assertRaisesRegex(ValueError, 'Git 무시'):
                validate_output(root, root / 'result.json')
            self.assertEqual(validate_output(root, root / 'tmp/result.json'), (root / 'tmp/result.json').resolve())
            (root / 'tmp').mkdir()
            tracked = root / 'tmp/tracked.json'
            tracked.write_text('original')
            subprocess.run(['git', 'add', '-f', 'tmp/tracked.json'], cwd=root, check=True)
            with self.assertRaisesRegex(ValueError, 'Git 무시'):
                validate_output(root, tracked)
            self.assertEqual(tracked.read_text(), 'original')
            outside = Path(directory) / 'outside.json'
            self.assertEqual(validate_output(root, outside), outside.resolve())

    def test_all_monitoring_targets_must_have_up_samples(self):
        self.assertTrue(targets_healthy([{'values': [[1, '1'], [2, '1']]}]))
        self.assertFalse(targets_healthy([{'values': [[1, '1'], [2, '0']]}]))
        self.assertFalse(targets_healthy([{'values': [[1, 'NaN']]}]))
        self.assertFalse(targets_healthy([{'values': []}]))
        self.assertFalse(targets_healthy([]))


class SockJSRotationTest(unittest.TestCase):
    def test_rotated_stream_keeps_session_subscriptions_and_ack_delivery(self):
        paths, posted = [], []
        finish = threading.Event()
        probe_received, allow_ready = threading.Event(), threading.Event()
        probe = {}

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_POST(self):
                body = self.rfile.read(int(self.headers['Content-Length']))
                if self.path.endswith('/xhr_send'):
                    frames = json.loads(body)
                    posted.extend(frames)
                    for frame in frames:
                        command, headers, payload = next(stomp_frames(frame))
                        if command == 'SEND':
                            probe.update(destination=headers['destination'], body=payload)
                            probe_received.set()
                    self.send_response(204)
                    self.end_headers()
                    return
                paths.append(self.path)
                self.send_response(200)
                self.end_headers()
                if len(paths) == 1:
                    self.wfile.write(b'o\n' + ('a' + json.dumps(['CONNECTED\nversion:1.2\n\n\0']) + '\n').encode())
                    self.wfile.flush()
                    allow_ready.wait(3)
                    frame = 'MESSAGE\ndestination:' + probe['destination'] + '\n\n' + probe['body'] + '\0'
                    self.wfile.write(('a' + json.dumps([frame]) + '\n').encode())
                else:
                    frame = 'MESSAGE\ndestination:/topic/ack/robot_1\n\n' + json.dumps({'cmdId': 'cmd', 'status': 'done'}) + '\0'
                    self.wfile.write(('a' + json.dumps([frame]) + '\n').encode())
                    self.wfile.flush()
                    finish.wait(3)

            def log_message(self, *args):
                pass

        server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        serving = threading.Thread(target=server.serve_forever, daemon=True)
        serving.start()
        client = Client(0, f'http://127.0.0.1:{server.server_port}', ['robot_1'])
        reader = threading.Thread(target=client.websocket, daemon=True)
        reader.start()
        try:
            self.assertTrue(probe_received.wait(3))
            self.assertFalse(client.ws_ready.is_set())
            allow_ready.set()
            self.assertTrue(client.ws_ready.wait(3))
            deadline = time.monotonic() + 3
            while not client.acks and time.monotonic() < deadline:
                time.sleep(.01)
            self.assertEqual(client.acks['cmd'][0][0]['status'], 'done')
            self.assertEqual(len(paths), 2)
            self.assertEqual(paths[0], paths[1])
            self.assertEqual(sum(frame.startswith('CONNECT\n') for frame in posted), 1)
            subscribed = [headers['destination'] for frame in posted
                          for command, headers, _ in stomp_frames(frame) if command == 'SUBSCRIBE']
            self.assertEqual(subscribed, ['/topic/cmd-result', '/topic/ack/robot_1', client.ready_destination])
            self.assertEqual(client.errors, [])
        finally:
            client.stop.set()
            allow_ready.set()
            finish.set()
            reader.join(3)
            server.shutdown()
            server.server_close()
            serving.join()
        self.assertFalse(reader.is_alive())
        self.assertEqual(client.errors, [])

    def test_terminal_close_frame_remains_a_connection_failure(self):
        class Handler(http.server.BaseHTTPRequestHandler):
            def do_POST(self):
                self.rfile.read(int(self.headers['Content-Length']))
                self.send_response(200)
                self.end_headers()
                self.wfile.write(b'c[3000,"closed"]\n')

            def log_message(self, *args):
                pass

        server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
        serving = threading.Thread(target=server.serve_forever, daemon=True)
        serving.start()
        client = Client(0, f'http://127.0.0.1:{server.server_port}', ['robot_1'])
        reader = threading.Thread(target=client.websocket, daemon=True)
        try:
            reader.start()
            reader.join(3)
            self.assertFalse(reader.is_alive())
            self.assertIn('SockJS session closed', client.errors[0])
        finally:
            client.stop.set()
            server.shutdown()
            server.server_close()
            serving.join()


if __name__ == '__main__':
    unittest.main()
