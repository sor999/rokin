import json
import logging
import threading
from typing import Callable

from confluent_kafka import Consumer, KafkaError, KafkaException

logger = logging.getLogger(__name__)


class KafkaCommandConsumer:
    """Kafka cmd.robot 토픽을 Consume해 콜백으로 명령을 전달하는 클래스.

    별도 데몬 스레드에서 실행되며, ROS 노드 생명주기와 함께 start/stop된다.
    """

    def __init__(self, broker: str, group_id: str, topic: str, on_message: Callable[[dict], None]):
        """
        Args:
            broker: Kafka 브로커 주소 (예: kafka:29092)
            group_id: Kafka Consumer 그룹 ID
            topic: 구독할 Kafka 토픽 (예: cmd.robot)
            on_message: 메시지 수신 시 호출할 콜백. dict(파싱된 JSON)을 인자로 받는다.
        """
        self._config = {
            'bootstrap.servers': broker,
            'group.id': group_id,
            'auto.offset.reset': 'latest',
            'enable.auto.commit': False,  # 수동 커밋 사용 (At-least-once 보장)
        }
        self._topic = topic
        self._on_message = on_message
        self._running = False
        self._thread: threading.Thread | None = None

    def start(self) -> None:
        """백그라운드 데몬 스레드에서 Kafka poll 루프를 시작한다."""
        self._running = True
        self._thread = threading.Thread(target=self._poll_loop, daemon=True, name='kafka-consumer')
        self._thread.start()
        logger.info(f"[Consumer] 구독 시작: topic={self._topic}")

    def stop(self) -> None:
        """poll 루프를 종료하고 Consumer를 닫는다."""
        self._running = False
        if self._thread:
            self._thread.join(timeout=5.0)
        logger.info("[Consumer] 종료 완료")

    def _poll_loop(self) -> None:
        consumer = Consumer(self._config)
        consumer.subscribe([self._topic])
        try:
            while self._running:
                msg = consumer.poll(timeout=1.0)
                if msg is None:
                    continue
                if msg.error():
                    # EOF는 정상 파티션 끝, 그 외는 실제 오류
                    if msg.error().code() != KafkaError._PARTITION_EOF:
                        logger.error(f"[Consumer] Kafka 오류: {msg.error()}")
                    continue

                try:
                    value = json.loads(msg.value().decode('utf-8'))
                    self._on_message(value)
                    # 성공적으로 처리된 경우에만 오프셋 커밋
                    consumer.commit(asynchronous=False)
                except (json.JSONDecodeError, UnicodeDecodeError) as e:
                    logger.warning(f"[Consumer] 메시지 파싱 실패: {e}")
                    # 파싱 실패한 메시지는 다시 시도해도 실패할 것이므로 커밋하여 넘김 (필요 시 DLQ 처리)
                    consumer.commit(asynchronous=False)
                except Exception:
                    logger.exception("[Consumer] 콜백 처리 중 예외 발생")
                    # 처리 실패 시 커밋하지 않음 -> 재시작 시 해당 메시지부터 다시 읽음
        except KafkaException:
            logger.exception("[Consumer] 치명적 Kafka 오류 발생")
        finally:
            consumer.close()
