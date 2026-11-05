"""Opt-in 전용 Compose의 부하, 장애와 증설 검증. 볼륨 및 오프셋을 초기화하지 않는다."""
import argparse
from datetime import datetime, timezone
import json
from pathlib import Path
import re
import subprocess
import sys
import time
import urllib.request
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'scalability'))
from lab_lock import lock

ROOT = Path(__file__).resolve().parents[2]
COMPOSE = ['docker', 'compose', '--project-name', 'rokin-worker-lab', '--env-file', '/dev/null', '-f', str(ROOT / 'infra/worker-lab/compose.yml')]


def command(args, **kwargs):
    return subprocess.check_output(args, text=True, timeout=60, **kwargs).strip()


def compose(*args):
    return command(COMPOSE + list(args))


def sql(query):
    return compose('exec', '-T', 'postgres', 'psql', '-v', 'ON_ERROR_STOP=1', '-U', 'postgres',
                   '-d', 'telemetry', '-Atc', query)


def metric_values(text, name):
    return [float(value) for value in re.findall(r'^' + re.escape(name) + r'(?:\{[^\n]*\})? ([^\n ]+)', text, re.M)]


def workers():
    return compose('ps', '-q', 'worker').splitlines()


def sample():
    result = []
    for container in workers():
        data = json.loads(command(['docker', 'inspect', container]))[0]
        ports = data['NetworkSettings']['Ports']['2112/tcp']
        port = ports[0]['HostPort']
        with urllib.request.urlopen(f'http://127.0.0.1:{port}/metrics', timeout=5) as response:
            text = response.read().decode()
        rss = metric_values(text, 'process_resident_memory_bytes')[0]
        container_bytes=int(command(['docker','exec',container,'sh','-c',
                                     'cat /sys/fs/cgroup/memory.current 2>/dev/null || cat /sys/fs/cgroup/memory/memory.usage_in_bytes']))
        if container_bytes >= 512 * 1024**2 or data['HostConfig']['Memory'] != 512 * 1024**2:
            raise AssertionError(f'container memory violation: {container_bytes}')
        if rss >= 512 * 1024**2 or data['State']['OOMKilled'] or data['RestartCount']:
            raise AssertionError(f'memory or restart violation: {container} RSS={rss}')
        result.append(dict(container=container[:12], rss=rss, container_bytes=container_bytes,
                           pending=metric_values(text, 'worker_pending'),
                           paused_partitions=sum(metric_values('\n'.join(line for line in text.splitlines() if 'kind="paused_partitions"' in line), 'worker_pending')),
                           controls=metric_values(text, 'worker_control_total')))
    if not result:
        raise AssertionError('no Worker running')
    return dict(at=datetime.now(timezone.utc).isoformat(), workers=result)


def lag():
    output = compose('exec', '-T', 'kafka', 'kafka-consumer-groups', '--bootstrap-server',
                     'kafka:9092', '--group', 'telemetry-worker', '--describe')
    values = []
    for line in output.splitlines():
        fields = line.split()
        if len(fields) >= 6 and fields[0] == 'telemetry-worker' and fields[5].isdigit():
            values.append(int(fields[5]))
    if len(values) != 12:
        raise AssertionError(f'expected 12 partition offsets, found {len(values)}: {output}')
    return sum(values)


def counts(run):
    # run은 이 프로세스에서 생성한 UUID의 hex 문자열이다.
    return {kind: int(sql(f"SELECT count(*) FROM {table} WHERE robot_id LIKE '{run}_%'"))
            for kind, table in [('pose', 'telemetry_pose'), ('battery', 'telemetry_battery'),
                                ('status', 'telemetry_status'), ('ack', 'robot_ack')]}


def wait_samples(seconds, samples):
    until = time.monotonic() + seconds
    while time.monotonic() < until:
        samples.append(sample())
        time.sleep(min(5, max(0, until - time.monotonic())))


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--outage-seconds', type=int, default=600)
    parser.add_argument('--recovery-seconds', type=int, default=600)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    if args.outage_seconds <= 0 or args.recovery_seconds <= 0:
        parser.error('durations must be positive')
    args.output.parent.mkdir(parents=True, exist_ok=True)
    run = uuid.uuid4().hex
    load_name = 'rokin-worker-lab-load-' + run[:8]
    samples = []
    result = dict(run=run, outage_seconds=args.outage_seconds, samples=samples)
    output = args.output.with_suffix('.producer.log')
    started = None
    try:
        compose('up', '-d', '--no-build', '--scale', 'worker=1', 'worker')
        ready_until=time.monotonic()+45
        while True:
            try:
                sample()
                break
            except (OSError, AssertionError):
                if time.monotonic()>ready_until:
                    raise
                time.sleep(1)
        with output.open('w') as handle:
            started = subprocess.Popen(COMPOSE + ['run', '--rm', '--name', load_name, '--no-deps', 'load',
                                       '--run', run, '--seconds', str(args.outage_seconds + args.recovery_seconds + 300)],
                                       stdout=handle, stderr=subprocess.STDOUT, text=True)
        print('baseline: 500 telemetry/s, 10 Ack/s', flush=True)
        wait_samples(30, samples)
        result['baseline_lag'] = lag()
        assert counts(run)['ack'] > 100, 'Ack did not start'

        print('pose-only table lock, Ack isolation', flush=True)
        since = datetime.now(timezone.utc).isoformat()
        held = subprocess.Popen(COMPOSE + ['exec', '-T', 'postgres', 'psql', '-v', 'ON_ERROR_STOP=1',
                                '-U', 'postgres', '-d', 'telemetry', '-c',
                                'BEGIN; LOCK TABLE telemetry_pose IN ACCESS EXCLUSIVE MODE; SELECT pg_sleep(25); COMMIT;'],
                                stdout=subprocess.DEVNULL)
        try:
            wait_samples(20, samples)
            assert int(sql("SELECT count(*) FROM pg_locks WHERE relation='telemetry_pose'::regclass "
                           "AND mode='AccessExclusiveLock' AND granted")) == 1, 'pose lock missing'
            result['ack_isolation_p95_seconds'] = float(sql(
                "SELECT coalesce(percentile_cont(.95) WITHIN GROUP (ORDER BY extract(epoch FROM updated_at-timestamp)),999) "
                f"FROM robot_ack WHERE robot_id LIKE '{run}_%' AND timestamp>'{since}'::timestamptz+interval '5 seconds'"))
            assert result['ack_isolation_p95_seconds'] <= 2, result
        finally:
            held.wait(timeout=35)
        wait_samples(10, samples)

        print(f'DB outage for {args.outage_seconds}s', flush=True)
        compose('stop', '-t', '5', 'postgres')
        outage_started = time.monotonic()
        outage_samples_start = len(samples)
        wait_samples(args.outage_seconds, samples)
        result['actual_outage_seconds'] = time.monotonic() - outage_started
        plateau = [s['workers'][0]['pending'] for s in samples[outage_samples_start + max(1, (len(samples)-outage_samples_start)//2):]]
        assert len(plateau) >= 2 and all(p == plateau[-1] for p in plateau), 'pending did not plateau'
        result['outage_pending_plateau'] = plateau[-1]
        compose('start', 'postgres')
        recovery_started = time.monotonic()
        print('DB recovery with same incoming rate', flush=True)
        while True:
            wait_samples(5, samples)
            current_lag = lag()
            print(f'recovery lag={current_lag}', flush=True)
            if current_lag <= result['baseline_lag'] + 510:
                break
            assert time.monotonic() - recovery_started < args.recovery_seconds, 'backlog recovery deadline exceeded'
        result['recovery_seconds'] = time.monotonic() - recovery_started
        result['recovered_lag'] = current_lag

        print('filled buffers and rebalance 1 -> 3 -> 1, graceful and forced stop', flush=True)
        compose('stop', '-t', '5', 'postgres')
        wait_samples(15, samples)
        # 새 Worker의 시작 스키마 확인을 위해 DB는 복구하고 즉시 증설한다.
        compose('start', 'postgres')
        compose('up', '-d', '--no-build', '--scale', 'worker=3', 'worker')
        wait_samples(25, samples)
        assert len(workers()) == 3
        victim = workers()[-1]
        command(['docker', 'kill', '--signal', 'SIGKILL', victim])
        compose('up', '-d', '--no-build', '--scale', 'worker=3', 'worker')
        wait_samples(20, samples)
        compose('up', '-d', '--no-build', '--scale', 'worker=1', 'worker')
        wait_samples(20, samples)
        assert len(workers()) == 1

        command(['docker', 'kill', '--signal', 'SIGTERM', load_name])
        started.wait(timeout=45)
        assert started.returncode == 0, output.read_text()[-2000:]
        producer = json.loads(output.read_text().splitlines()[-1])
        result['producer'] = producer
        until = time.monotonic() + args.recovery_seconds
        while True:
            actual = counts(run)
            if actual == producer['counts']:
                break
            assert time.monotonic() < until, f"missing rows: {actual}, expected {producer['counts']}"
            wait_samples(5, samples)
        result['persisted'] = actual
        result['max_rss_bytes'] = max(w['rss'] for s in samples for w in s['workers'])
        result['max_container_bytes'] = max(w['container_bytes'] for s in samples for w in s['workers'])
        result['passed'] = True
        print(json.dumps({k: v for k, v in result.items() if k != 'samples'}), flush=True)
    finally:
        # 실패해도 원본과 결과를 남기고 DB를 다시 기동한다. 볼륨은 삭제하지 않는다.
        if started is not None and started.poll() is None:
            subprocess.run(['docker', 'kill', '--signal', 'SIGTERM', load_name], capture_output=True, timeout=10)
            started.wait(timeout=45)
        subprocess.run(COMPOSE + ['start', 'postgres'], capture_output=True, timeout=30)
        args.output.write_text(json.dumps(result, indent=2) + '\n')


if __name__ == '__main__':
    with lock(ROOT / 'tmp/worker-lab/.lab.lock'):
        main()
