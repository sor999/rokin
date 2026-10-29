import base64
import json
import logging
import threading
from typing import Callable

from confluent_kafka import Consumer, KafkaError, Producer

from .commands import CommandRejected

logger = logging.getLogger(__name__)


class KafkaCommandConsumer:
    """명령 전달 또는 DLQ 발행 확인 후 해당 메시지만 동기 커밋한다."""

    def __init__(self, broker: str, group_id: str, topic: str,
                 on_message: Callable[[dict], None], dlq_topic: str = 'dlq.command',
                 consumer_factory=Consumer, producer_factory=Producer, retry_seconds=1.0):
        self._config = {
            'bootstrap.servers': broker,
            'group.id': group_id,
            'auto.offset.reset': 'latest',
            'enable.auto.commit': False,
            'enable.auto.offset.store': False,
        }
        self._producer_config = {'bootstrap.servers': broker, 'enable.idempotence': True}
        self._topic = topic
        self._dlq_topic = dlq_topic
        self._on_message = on_message
        self._consumer_factory = consumer_factory
        self._producer_factory = producer_factory
        self._retry_seconds = retry_seconds
        self._stop = threading.Event()
        self.ready = threading.Event()
        self._thread = None

    def start(self) -> None:
        """백그라운드 소비를 시작하며 할당과 초기 offset 확인 후 ready를 설정한다."""
        self._stop.clear()
        self.ready.clear()
        self._thread = threading.Thread(target=self._poll_loop, daemon=True, name='kafka-consumer')
        self._thread.start()

    def stop(self) -> None:
        """소비 종료를 요청하고, 15초 안에 스레드가 끝나지 않으면 예외를 발생시킨다."""
        self._stop.set()
        self.ready.clear()
        if self._thread:
            self._thread.join(timeout=15.0)
            if self._thread.is_alive():
                raise RuntimeError('Command consumer did not stop')

    def _send_dlq(self, producer, message, reason):
        """원본과 실패 원인을 보존하며 Kafka의 DLQ 전달 확인을 기다린다.

        전달 실패나 시간 초과는 예외로 전파해 원본 offset의 커밋을 막는다.
        """
        payload = {
            'source_topic': message.topic(),
            'source_partition': message.partition(),
            'source_offset': message.offset(),
            'original_payload_base64': base64.b64encode(message.value() or b'').decode('ascii'),
            'error': str(reason),
        }
        result = []
        producer.produce(
            self._dlq_topic, key=message.key(), value=json.dumps(payload).encode('utf-8'),
            on_delivery=lambda error, delivered: result.append(error),
        )
        remaining = producer.flush(timeout=10.0)
        if remaining or not result or result[0] is not None:
            raise RuntimeError('Command DLQ delivery was not acknowledged')

    def _process(self, consumer, producer, message):
        """콜백 성공 또는 DLQ 전달 확인 이후에만 이 메시지의 offset을 커밋한다."""
        try:
            value = json.loads(message.value())
        except (ValueError, UnicodeDecodeError, TypeError, RecursionError) as error:
            self._send_dlq(producer, message, error)
        else:
            try:
                self._on_message(value)
            except CommandRejected as error:
                self._send_dlq(producer, message, error)
        self._check_commit(consumer.commit(message=message, asynchronous=False))

    @staticmethod
    def _check_commit(partitions):
        if partitions and any(partition.error for partition in partitions):
            raise RuntimeError('Command offset commit failed')

    def _on_assign(self, consumer, partitions):
        """기존 offset에서 재개하고 새 파티션은 latest를 먼저 저장한 뒤 ready를 설정한다.

        초기 offset 저장에 실패하면 준비 완료로 표시하지 않는다. 첫 명령 처리 실패 후
        재연결해도 latest로 건너뛰지 않고 저장된 시작 위치에서 재시도한다.
        """
        self.ready.clear()
        committed = consumer.committed(partitions, timeout=10.0)
        self._check_commit(committed)
        initial = []
        for partition in committed:
            if partition.offset < 0:
                low, high = consumer.get_watermark_offsets(partition, timeout=10.0)
                partition.offset = high
                initial.append(partition)
        consumer.assign(committed)
        if initial:
            self._check_commit(consumer.commit(offsets=initial, asynchronous=False))
        self.ready.set()
        logger.info('Command consumer assignment ready: %s', self._config['group.id'])

    def _on_revoke(self, consumer, partitions):
        self.ready.clear()

    def _consume(self, consumer, producer):
        while not self._stop.is_set():
            message = consumer.poll(timeout=1.0)
            if message is None:
                continue
            if message.error():
                if message.error().code() != KafkaError._PARTITION_EOF:
                    logger.error('Kafka command error: %s', message.error())
                continue
            for attempt in range(3):
                if self._stop.is_set():
                    return
                try:
                    self._process(consumer, producer, message)
                    break
                except Exception:
                    logger.exception('Command processing failed; offset retained')
                    if attempt == 2:
                        raise
                    self._stop.wait(self._retry_seconds)

    def _poll_loop(self) -> None:
        while not self._stop.is_set():
            consumer = None
            try:
                consumer = self._consumer_factory(self._config)
                producer = self._producer_factory(self._producer_config)
                consumer.subscribe([self._topic], on_assign=self._on_assign,
                                   on_revoke=self._on_revoke, on_lost=self._on_revoke)
                self._consume(consumer, producer)
            except Exception:
                logger.exception('Reconnecting command consumer from committed offsets')
            finally:
                self.ready.clear()
                if consumer is not None:
                    consumer.close()
            self._stop.wait(self._retry_seconds)
