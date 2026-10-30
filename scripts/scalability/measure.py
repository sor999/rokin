import argparse
import datetime as dt
import hashlib
import json
import math
import pathlib
import re
import subprocess
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

from lab_lock import lock
from provenance import source_fingerprint

ROOT = pathlib.Path(__file__).resolve().parents[2]
ORIGIN = 'http://localhost:13000'


def request(url, body=None, timeout=10):
    """JSON 요청을 전송하고 본문이 없는 SockJS 204 응답도 허용한다."""
    payload = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(url, data=payload, headers={
        'Content-Type': 'application/json', 'Origin': ORIGIN})
    with urllib.request.urlopen(req, timeout=timeout) as response:
        content = response.read()
        return json.loads(content) if content else None


def percentile(values, p):
    """가장 가까운 순위 방식의 백분위수를 반환하며 빈 표본은 None으로 표시한다."""
    if not values:
        return None
    return sorted(values)[max(0, math.ceil(len(values) * p) - 1)]


def timestamp(value):
    """Java ISO 시간의 가변 소수점 정밀도를 Python 3.9에 맞춰 해석한다."""
    normalized = re.sub(r'\.(\d+)(?=Z|[+-]\d{2}:\d{2}$)',
                        lambda match: '.' + match[1][:6].ljust(6, '0'), value)
    return dt.datetime.fromisoformat(normalized.replace('Z', '+00:00')).timestamp()


def stomp_frames(payload):
    """하트비트와 NUL 구분자를 처리해 STOMP 명령·헤더·본문을 분리한다."""
    for frame in payload.split('\0'):
        frame = frame.lstrip('\n')
        if not frame:
            continue
        head, _, body = frame.partition('\n\n')
        lines = head.splitlines()
        headers = dict(line.split(':', 1) for line in lines[1:] if ':' in line)
        yield lines[0], headers, body


class Client:
    def __init__(self, index, url, robots):
        self.index, self.url, self.robots = index, url, robots
        self.session = f'{url}/ws/000/{uuid.uuid4().hex}'
        self.sse_ready, self.ws_ready = threading.Event(), threading.Event()
        self.stop = threading.Event()
        self.events, self.results, self.acks, self.errors = [], {}, {}, []
        self.http_latencies = []
        self.lock = threading.Lock()

    def send(self, command, headers, body=''):
        """현재 SockJS 세션으로 NUL 종료 STOMP 프레임을 전송한다."""
        frame = command + '\n' + ''.join(f'{k}:{v}\n' for k, v in headers.items()) + '\n' + body + '\0'
        request(self.session + '/xhr_send', [frame])

    def sse(self):
        """관측 이벤트를 기록하고 측정 중 연결 종료나 파싱 실패를 보존한다."""
        try:
            req = urllib.request.Request(self.url + '/api/stream/telemetry', headers={'Origin': ORIGIN})
            with urllib.request.urlopen(req, timeout=20) as response:
                self.sse_ready.set()
                data = []
                while not self.stop.is_set():
                    raw = response.readline()
                    if not raw:
                        break
                    line = raw.decode().strip()
                    if not line and data:
                        event = json.loads('\n'.join(data))
                        data = []
                        if event.get('type') in ('pose', 'battery', 'status') and event.get('robotId') in self.robots:
                            received = time.time()
                            published = timestamp(event['timestamp'])
                            key = hashlib.sha256(json.dumps(event, sort_keys=True).encode()).hexdigest()
                            with self.lock:
                                self.events.append((key, published, received))
                    elif line.startswith('data:'):
                        data.append(line[5:].strip())
                if not self.stop.is_set():
                    self.errors.append('SSE closed')
        except Exception as error:
            if not self.stop.is_set():
                self.errors.append(f'SSE: {type(error).__name__}: {error}')

    def websocket(self):
        """같은 SockJS 세션의 HTTP 스트림 교체를 허용하고 실제 세션 종료는 기록한다."""
        opened = False
        empty_streams = 0
        try:
            while not self.stop.is_set():
                req = urllib.request.Request(self.session + '/xhr_streaming', data=b'', headers={'Origin': ORIGIN})
                received_frame = False
                with urllib.request.urlopen(req, timeout=60) as response:
                    while not self.stop.is_set():
                        line = response.readline()
                        if not line:
                            break
                        line = line.decode().strip()
                        if line == 'o':
                            if opened:
                                raise RuntimeError('SockJS session unexpectedly reopened')
                            opened = True
                            received_frame = True
                            self.send('CONNECT', {'accept-version': '1.2', 'host': 'localhost', 'heart-beat': '0,0'})
                        elif line == 'h':
                            received_frame = True
                        elif line.startswith('a'):
                            received_frame = True
                            for payload in json.loads(line[1:]):
                                for command, headers, body in stomp_frames(payload):
                                    if command == 'CONNECTED':
                                        destinations = ['/topic/cmd-result'] + [f'/topic/ack/{r}' for r in self.robots]
                                        for index, destination in enumerate(destinations):
                                            self.send('SUBSCRIBE', {'id': str(index), 'destination': destination})
                                        self.ws_ready.set()
                                    elif command == 'MESSAGE':
                                        value = json.loads(body)
                                        with self.lock:
                                            if headers.get('destination') == '/topic/cmd-result':
                                                self.results[value['requestId']] = (value, time.time())
                                            else:
                                                self.acks.setdefault(value['cmdId'], []).append((value, time.time()))
                                    elif command == 'ERROR':
                                        raise RuntimeError('STOMP ERROR: ' + body[:200])
                        elif line.startswith('c'):
                            raise RuntimeError('SockJS session closed: ' + line[1:])
                empty_streams = 0 if received_frame else empty_streams + 1
                if empty_streams >= 3:
                    raise RuntimeError('SockJS repeatedly returned an empty stream')
                self.stop.wait(.05)
        except Exception as error:
            if not self.stop.is_set():
                self.errors.append(f'SockJS: {type(error).__name__}: {error}')

    def start(self):
        """SSE·SockJS·REST 부하를 독립 스레드에서 시작한다."""
        for target in (self.sse, self.websocket, self.poll):
            threading.Thread(target=target, daemon=True).start()

    def poll(self):
        """초기 목록 조회 후 등록 목록을 2초마다 조회해 지연과 오류를 기록한다."""
        try:
            request(self.url + '/api/robots')
        except Exception as error:
            self.errors.append(f'initial REST: {error}')
        while not self.stop.is_set():
            started = time.time()
            try:
                request(self.url + '/api/simulations')
                with self.lock:
                    self.http_latencies.append((started, time.time() - started))
            except Exception as error:
                self.errors.append(f'REST polling: {error}')
            self.stop.wait(2)


def prepare(robots):
    """기존 등록을 보존해 프로필 로봇을 준비하고 실행 상태를 기다린다."""
    url = 'http://localhost:18081'
    desired = ['robot_1', 'robot_2', 'robot_3'] + [f'scale_robot_{n}' for n in range(4, robots + 1)]
    existing = request(url + '/api/simulations')
    if {r['robotId'] for r in existing} - set(desired):
        raise RuntimeError('프로필 밖의 등록 이력이 있습니다. 데이터를 삭제하지 않고 중단합니다. 같은 프로필을 사용하세요.')
    for robot in desired:
        found = next((r for r in existing if r['robotId'] == robot), None)
        if found and found['desiredState'] != 'RUNNING':
            raise RuntimeError(f'{robot}: 삭제된 ID를 재사용할 수 없습니다.')
        if not found:
            request(url + '/api/simulations', {'robotId': robot, 'startX': 0, 'startY': 0, 'speed': 1})
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        rows = request(url + '/api/simulations')
        if len(rows) == robots and all(r['state'] == 'RUNNING' for r in rows):
            return desired
        time.sleep(2)
    raise RuntimeError('로봇 준비 시간 초과: ' + json.dumps(rows, ensure_ascii=False))


def database_snapshot():
    """격리 프로젝트 DB의 누적 쿼리 통계와 현재 락·연결 상태를 읽는다."""
    sql = """SELECT json_build_object(
      'calls', (SELECT coalesce(sum(calls),0) FROM pg_stat_statements WHERE dbid=(SELECT oid FROM pg_database WHERE datname=current_database())),
      'total_exec_ms', (SELECT coalesce(sum(total_exec_time),0) FROM pg_stat_statements WHERE dbid=(SELECT oid FROM pg_database WHERE datname=current_database())),
      'waiting_locks', (SELECT count(*) FROM pg_locks WHERE NOT granted),
      'lock_waiters', (SELECT count(*) FROM pg_stat_activity WHERE wait_event_type='Lock'),
      'active_connections', (SELECT count(*) FROM pg_stat_activity WHERE datname=current_database()),
      'database_bytes', pg_database_size(current_database()));"""
    ids = subprocess.check_output(['docker', 'ps', '-q', '--filter', 'label=com.docker.compose.project=rokin-scale-lab',
                                   '--filter', 'label=com.docker.compose.service=postgres'], text=True).split()
    if len(ids) != 1:
        raise RuntimeError('격리 프로젝트 PostgreSQL을 확인할 수 없습니다.')
    return json.loads(subprocess.check_output(['docker', 'exec', ids[0], 'psql', '-U', 'postgres', '-d', 'telemetry', '-Atc', sql], text=True))


def verify_lab(mode):
    """측정 대상 컨테이너·모드·loopback 포트가 격리 설정과 일치하는지 확인한다."""
    if mode == 'single':
        extra = subprocess.check_output(['docker', 'ps', '-q', '--filter',
            'label=com.docker.compose.project=rokin-scale-lab', '--filter',
            'label=com.docker.compose.service=api-b'], text=True).strip()
        if extra:
            raise RuntimeError('single 측정 전에 API B를 정지하세요.')
    services = {'api': '18081', 'load-balancer': '18080'}
    if mode == 'dual':
        services['api-b'] = '18082'
    for service, port in services.items():
        ids = subprocess.check_output(['docker', 'ps', '-q', '--filter',
            'label=com.docker.compose.project=rokin-scale-lab', '--filter',
            f'label=com.docker.compose.service={service}'], text=True).split()
        if len(ids) != 1:
            raise RuntimeError(f'격리 서비스가 준비되지 않았습니다: {service}')
        ports = json.loads(subprocess.check_output(['docker', 'inspect', '--format',
                           '{{json .NetworkSettings.Ports}}', ids[0]], text=True))
        if ports.get('8080/tcp') != [{'HostIp': '127.0.0.1', 'HostPort': port}]:
            raise RuntimeError(f'격리 포트 매핑이 다릅니다: {service}')


QUERIES = {
    'kafka_lag': 'kafka_consumergroup_lag',
    'worker_buffer': 'worker_batch_buffer_depth',
    'worker_memory_bytes': 'process_resident_memory_bytes{job="worker-go"}',
    'worker_heap_bytes': 'go_memstats_alloc_bytes{job="worker-go"}',
    'api_heap_bytes': 'sum by(instance)(jvm_memory_used_bytes{area="heap"})',
    'api_total_heap_bytes': 'sum(jvm_memory_used_bytes{area="heap"})',
    'api_process_memory_bytes': 'process_resident_memory_bytes{job="api-spring"}',
    'api_http_latency_p95': 'histogram_quantile(0.95,sum by(le,instance)(rate(http_server_requests_seconds_bucket[1m])))',
    'sse_event_lag_p95': 'histogram_quantile(0.95,sum by(le,instance)(rate(fleet_sse_event_lag_seconds_bucket[1m])))',
    'db_connections': 'pg_stat_database_numbackends',
    'target_up': 'up',
}


def metrics_snapshot(start, end):
    """측정 구간의 Prometheus 시계열을 읽고 조회 실패는 실행 오류로 처리한다."""
    result = {}
    for name, query in QUERIES.items():
        params = urllib.parse.urlencode({'query': query, 'start': start, 'end': end, 'step': 5})
        value = request('http://localhost:19090/api/v1/query_range?' + params)
        if value.get('status') != 'success':
            raise RuntimeError(f'Prometheus query failed: {name}')
        result[name] = value['data']['result']
    return result


def summarize(clients, commands, start, end, profile):
    """생성량·클라이언트별 전달률·명령 접수·Ack 지연을 함께 판정한다."""
    snapshots = []
    for client in clients:
        with client.lock:
            snapshots.append((list(client.events), dict(client.results), dict(client.acks), list(client.errors)))
    events = [{key for key, published, _ in snapshot[0] if start <= published <= end} for snapshot in snapshots]
    union = set().union(*events)
    accepted, acceptance_lag, ack_lag, reports = {}, [], [], []
    for request_id, sender, sent_at in commands:
        response = snapshots[sender][1].get(request_id)
        if response:
            accepted[response[0]['cmdId']] = sent_at
            acceptance_lag.append(response[1] - sent_at)
    for client, snapshot, keys in zip(clients, snapshots, events):
        terminals = {cmd: next((received for value, received in snapshot[2].get(cmd, []) if value['status'] == 'done'), None)
                     for cmd in accepted}
        delays = [received - accepted[cmd] for cmd, received in terminals.items() if received is not None]
        ack_lag.extend(delays)
        lag = [received - published for _, published, received in snapshot[0] if start <= published <= end]
        reports.append({'client': client.index, 'api': client.url, 'events': len(keys),
                        'event_delivery_ratio': len(keys) / len(union) if union else 0,
                        'terminal_ack_ratio': len(delays) / len(accepted) if accepted else 0,
                        'event_lag_p95_seconds': percentile(lag, .95), 'errors': snapshot[3]})
    threshold = profile['limits']
    expected = profile['robots'] * 3 * 2 * (end - start)
    checks = {
        'stream_connections': not any(s[3] for s in snapshots),
        'event_source_rate': len(union) >= expected * threshold['min_event_source_ratio'],
        'event_delivery': all(r['event_delivery_ratio'] >= threshold['min_delivery_ratio'] for r in reports),
        'command_acceptance': bool(commands) and len(accepted) / len(commands) >= threshold['min_acceptance_ratio'],
        'terminal_ack_delivery': bool(accepted) and all(r['terminal_ack_ratio'] >= threshold['min_delivery_ratio'] for r in reports),
        'event_latency': all(r['event_lag_p95_seconds'] is not None and r['event_lag_p95_seconds'] <= threshold['event_p95_seconds'] for r in reports),
        'ack_latency': bool(ack_lag) and percentile(ack_lag, .95) <= threshold['ack_p95_seconds'],
    }
    return {'checks': checks, 'passed': all(checks.values()), 'clients': reports, 'unique_events': len(union),
            'commands_sent': len(commands), 'commands_accepted': len(accepted),
            'command_error_ratio': 1 - len(accepted) / len(commands) if commands else None,
            'acceptance_p95_seconds': percentile(acceptance_lag, .95), 'ack_p95_seconds': percentile(ack_lag, .95)}


def run(args):
    """고정 프로필의 부하와 지표를 수집하고 소스 동일성을 확인해 결과를 저장한다."""
    fingerprint = source_fingerprint(ROOT)
    verify_lab(args.mode)
    profiles = json.loads((ROOT / 'scripts/scalability/profiles.json').read_text())
    profile = {**profiles[args.profile], 'limits': {'min_event_source_ratio': .90, 'min_delivery_ratio': .99,
               'min_acceptance_ratio': .99, 'event_p95_seconds': 2, 'ack_p95_seconds': 5}}
    robots = prepare(profile['robots'])
    endpoints = ['http://localhost:18081'] if args.mode == 'single' else ['http://localhost:18081', 'http://localhost:18082']
    clients = [Client(i, endpoints[i % len(endpoints)], robots) for i in range(profile['clients'])]
    for client in clients:
        client.start()
    deadline = time.monotonic() + 30
    while not all(c.sse_ready.is_set() and c.ws_ready.is_set() for c in clients):
        if time.monotonic() >= deadline or any(c.errors for c in clients):
            raise RuntimeError('연결 준비 실패: ' + json.dumps([c.errors for c in clients]))
        time.sleep(.1)
    time.sleep(3)
    commands = []
    before = database_snapshot()
    start = time.time()
    next_command = start
    end = start + profile['duration_seconds']
    db_samples = [before]
    next_sample = start + 5
    try:
        while time.time() < end:
            now = time.time()
            if now >= next_command and now < end - 5:
                sender = len(commands) % len(clients)
                request_id = uuid.uuid4().hex
                command = {'requestId': request_id, 'robotId': robots[len(commands) % len(robots)],
                           'command': 'stop', 'data': {}, 'issuedBy': 'scale-lab'}
                commands.append((request_id, sender, now))
                try:
                    clients[sender].send('SEND', {'destination': '/app/command', 'content-type': 'application/json'}, json.dumps(command))
                except Exception as error:
                    clients[sender].errors.append(f'command send: {error}')
                next_command += profile['command_interval_seconds']
            if now >= next_sample:
                db_samples.append(database_snapshot())
                next_sample += 5
            time.sleep(.05)
        time.sleep(2)
        report = summarize(clients, commands, start, end, profile)
        report.update({'mode': args.mode, 'profile': profile, 'start': start, 'end': end,
                       'simulation_config': request('http://localhost:18081/api/simulations'),
                       'docker_host': json.loads(subprocess.check_output(['docker', 'info', '--format',
                           '{"cpus":{{.NCPU}},"memory_bytes":{{.MemTotal}},"architecture":"{{.Architecture}}","version":"{{.ServerVersion}}"}'], text=True)),
                       'git_revision': subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip(),
                       'working_tree': subprocess.check_output(['git', 'status', '--short'], cwd=ROOT, text=True).splitlines(),
                       'source_fingerprint': fingerprint,
                       'database': {'before': before, 'after': database_snapshot(), 'samples': db_samples},
                       'prometheus': metrics_snapshot(start, end)})
        metrics = report['prometheus']
        rest_delays = [delay for client in clients for sent, delay in list(client.http_latencies) if start <= sent <= end]
        report['rest_poll_p95_seconds'] = percentile(rest_delays, .95)
        report['rest_poll_count'] = len(rest_delays)
        report['checks']['rest_latency'] = bool(rest_delays) and percentile(rest_delays, .95) <= 1
        report['checks']['metrics_present'] = all(metrics[key] for key in (
            'kafka_lag', 'worker_buffer', 'worker_memory_bytes', 'api_heap_bytes', 'db_connections'))
        report['passed'] = all(report['checks'].values())
        ids = subprocess.check_output(['docker', 'ps', '-q', '--filter', 'label=com.docker.compose.project=rokin-scale-lab'], text=True).split()
        report['docker_stats'] = subprocess.check_output(['docker', 'stats', '--no-stream', '--format', '{{json .}}', *ids], text=True).splitlines()
        output = pathlib.Path(args.output)
        if source_fingerprint(ROOT) != fingerprint:
            raise RuntimeError('측정 중 소스가 변경되어 결과를 저장하지 않습니다. 같은 코드로 다시 실행하세요.')
        output.parent.mkdir(parents=True, exist_ok=True)
        pending = output.with_name(output.name + '.pending')
        pending.write_text(json.dumps(report, ensure_ascii=False, indent=2) + '\n')
        pending.replace(output)
        print(json.dumps({key: report[key] for key in ('mode', 'passed', 'checks', 'commands_sent', 'commands_accepted')}, ensure_ascii=False, indent=2))
        print(f'결과: {output}')
        return 0 if report['passed'] or not args.enforce else 1
    finally:
        for client in clients:
            client.stop.set()


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--mode', choices=['single', 'dual'], required=True)
    parser.add_argument('--profile', choices=['smoke', 'baseline', 'fleet10'], default='baseline')
    parser.add_argument('--output', required=True)
    parser.add_argument('--enforce', action='store_true')
    with lock():
        raise SystemExit(run(parser.parse_args()))
