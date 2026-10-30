import argparse
import json
import math
from pathlib import Path


def validate_pair(single, dual):
    """프로필·revision·작업 트리와 실제 소스 내용이 같은 실행만 허용한다."""
    if single['mode'] != 'single' or dual['mode'] != 'dual':
        raise ValueError('single, dual 순서로 결과 파일을 지정하세요.')
    for key in ('profile', 'git_revision', 'working_tree'):
        if single.get(key) != dual.get(key):
            raise ValueError(f'같은 {key}의 실행만 비교할 수 있습니다.')
    if not single.get('source_fingerprint') or not dual.get('source_fingerprint'):
        raise ValueError('소스 fingerprint가 없는 이전 결과입니다. 두 모드를 다시 측정하세요.')
    if single['source_fingerprint'] != dual['source_fingerprint']:
        raise ValueError('소스 내용이 다른 실행은 비교할 수 없습니다.')


def overview(report):
    """전달률과 관측 자원 최댓값 및 DB 누적 통계 차이를 요약한다."""
    clients = report['clients']
    before, after = report['database']['before'], report['database']['after']
    duration = report['end'] - report['start']
    calls = after['calls'] - before['calls']
    execution_ms = after['total_exec_ms'] - before['total_exec_ms']
    maxima = {}
    for name in ('kafka_lag', 'worker_buffer', 'worker_memory_bytes', 'api_heap_bytes', 'api_total_heap_bytes'):
        values = [float(value) for series in report['prometheus'][name] for _, value in series['values']]
        values = [value for value in values if math.isfinite(value)]
        maxima[name] = max(values) if values else None
    return {
        'passed': report['passed'],
        'min_event_delivery_ratio': min(c['event_delivery_ratio'] for c in clients),
        'min_terminal_ack_ratio': min(c['terminal_ack_ratio'] for c in clients),
        'command_error_ratio': report['command_error_ratio'],
        'event_p95_seconds_worst_client': max((c['event_lag_p95_seconds'] for c in clients
                                             if c['event_lag_p95_seconds'] is not None), default=None),
        'ack_p95_seconds': report['ack_p95_seconds'],
        'db_queries_per_second': calls / duration,
        'db_exec_ms_per_second': execution_ms / duration,
        'db_mean_exec_ms': execution_ms / calls if calls else None,
        'db_max_lock_waiters': max(row['lock_waiters'] for row in report['database']['samples']),
        'observed_maxima': maxima,
    }


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('single')
    parser.add_argument('dual')
    args = parser.parse_args()
    single, dual = (json.loads(Path(path).read_text()) for path in (args.single, args.dual))
    try:
        validate_pair(single, dual)
    except ValueError as error:
        parser.error(str(error))
    print(json.dumps({'single': overview(single), 'dual': overview(dual)}, ensure_ascii=False, indent=2))
