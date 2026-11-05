"""가득 찬 버퍼를 유지한 DB 장애 중 실제 파티션 회수와 강제 종료를 검증한다."""
import argparse
import json
from pathlib import Path
import subprocess
import time
import uuid

from verify import COMPOSE, ROOT, command, compose, counts, lag, sample, wait_samples
from lab_lock import lock


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    run = uuid.uuid4().hex
    name = 'rokin-worker-lab-rebalance-' + run[:8]
    log = args.output.with_suffix('.producer.log')
    result = dict(run=run, samples=[])
    load = None
    try:
        compose('up', '-d', '--no-build', '--no-deps', '--scale', 'worker=3', 'worker')
        time.sleep(5)
        with log.open('w') as handle:
            load = subprocess.Popen(COMPOSE + ['run', '--rm', '--name', name, '--no-deps', 'load',
                                    '--run', run, '--seconds', '240'], stdout=handle, stderr=subprocess.STDOUT)
        wait_samples(20, result['samples'])
        before = counts(run)
        result['baseline_lag'] = lag()
        compose('stop', '-t', '5', 'postgres')
        wait_samples(30, result['samples'])
        states = result['samples'][-1]['workers']
        assert len(states) == 3
        assert all(w['paused_partitions'] > 0 for w in states), 'not all Workers filled and paused'
        # DB가 중단된 채 강제 종료와 정상 축소를 수행한다. 새 Worker는 시작하지 않는다.
        containers = compose('ps', '-q', 'worker').splitlines()
        command(['docker', 'kill', '--signal', 'SIGKILL', containers[-1]])
        started = time.monotonic()
        compose('up', '-d', '--no-build', '--no-deps', '--scale', 'worker=1', 'worker')
        result['downscale_seconds'] = time.monotonic() - started
        assert result['downscale_seconds'] <= 30, 'shutdown exceeded Compose grace'
        wait_samples(20, result['samples'])
        assert len(result['samples'][-1]['workers']) == 1
        result['filled_rebalance_pending'] = result['samples'][-1]['workers'][0]['pending']
        compose('start', 'postgres')
        wait_samples(20, result['samples'])
        assert all(counts(run)[kind] > value for kind, value in before.items()), 'no progress after recovery'
        command(['docker', 'kill', '--signal', 'SIGTERM', name])
        load.wait(timeout=45)
        assert load.returncode == 0, log.read_text()[-2000:]
        producer = json.loads(log.read_text().splitlines()[-1])
        result['producer'] = producer
        deadline = time.monotonic() + 120
        while counts(run) != producer['counts']:
            assert time.monotonic() < deadline, 'missing events after rebalance'
            wait_samples(5, result['samples'])
        result['persisted'] = counts(run)
        result['passed'] = True
        print(json.dumps({k: v for k, v in result.items() if k != 'samples'}), flush=True)
    finally:
        if load is not None and load.poll() is None:
            subprocess.run(['docker', 'kill', '--signal', 'SIGTERM', name], capture_output=True, timeout=10)
            load.wait(timeout=45)
        subprocess.run(COMPOSE + ['start', 'postgres'], capture_output=True, timeout=30)
        args.output.write_text(json.dumps(result, indent=2) + '\n')


if __name__ == '__main__':
    with lock(ROOT / 'tmp/worker-lab/.lab.lock'):
        main()
