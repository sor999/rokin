"""격리 Worker 검증용 일정 속도 부하. Kafka 전달 확인 건수를 마지막 JSON으로 출력한다."""
import argparse
from datetime import datetime, timezone
import json
import signal
import time
import uuid

from confluent_kafka import Producer


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--run', required=True)
    parser.add_argument('--seconds', type=float, required=True)
    parser.add_argument('--telemetry-rate', type=int, default=500)
    parser.add_argument('--ack-rate', type=int, default=10)
    args = parser.parse_args()
    producer = Producer({'bootstrap.servers': 'kafka:9092', 'enable.idempotence': True,
                         'acks': 'all', 'queue.buffering.max.messages': 5000,
                         'message.timeout.ms': 30000})
    counts = dict(pose=0, battery=0, status=0, ack=0)
    errors = []

    def delivered(kind, error, _):
        if error:
            errors.append(str(error))
        else:
            counts[kind] += 1

    stopping = False
    def stop(*_):
        nonlocal stopping
        stopping = True
    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    started = time.monotonic()
    sent = dict(telemetry=0, ack=0)
    while not stopping and time.monotonic() - started < args.seconds:
        elapsed = time.monotonic() - started
        for lane, rate in [('telemetry', args.telemetry_rate), ('ack', args.ack_rate)]:
            target = int(min(elapsed, args.seconds) * rate)
            while sent[lane] < target:
                index = sent[lane]
                robot = f'{args.run}_{index % 3}'
                timestamp = datetime.now(timezone.utc).isoformat()
                if lane == 'ack':
                    kind, topic = 'ack', 'ack.robot'
                    payload = dict(cmd_id=str(uuid.uuid5(uuid.NAMESPACE_URL, f'{args.run}/{index}')),
                                   robot_id=robot, timestamp=timestamp, status='done', message='load')
                else:
                    kind = ['pose'] * 8 + ['battery', 'status']
                    kind = kind[index % 10]
                    topic = f'telemetry.{kind}'
                    payload = dict(event_id=str(uuid.uuid5(uuid.NAMESPACE_URL, f'{args.run}/{index}')),
                                   robot_id=robot, timestamp=timestamp, type=kind,
                                   data=dict(x=index, y=1, level=90, state='idle'))
                value = json.dumps(payload).encode()
                while True:
                    try:
                        producer.produce(topic, value, partition=index % 3,
                                         on_delivery=lambda err, msg, kind=kind: delivered(kind, err, msg))
                        break
                    except BufferError:
                        producer.poll(.1)
                sent[lane] += 1
        producer.poll(.01)
    remaining = producer.flush(35)
    result = dict(run=args.run, counts=counts, sent=sent, remaining=remaining, errors=errors,
                  elapsed=time.monotonic() - started)
    print(json.dumps(result), flush=True)
    if remaining or errors:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
