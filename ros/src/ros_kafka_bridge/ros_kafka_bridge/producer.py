import json
import logging

from confluent_kafka import Producer, KafkaException

logger = logging.getLogger(__name__)


class KafkaTelemetryProducer:
    """ROS 텔레메트리 데이터를 Kafka 토픽에 Produce하는 클래스."""

    def __init__(self, broker: str):
        self._producer = Producer({'bootstrap.servers': broker})

    def produce(self, topic: str, key: str, value: dict) -> None:
        """메시지를 지정한 Kafka 토픽에 비동기 Produce한다.

        Args:
            topic: 대상 Kafka 토픽 이름
            key: 파티션 라우팅에 쓰일 키 (보통 robot_id)
            value: JSON 직렬화 가능한 dict (Envelope 포함)
        """
        try:
            self._producer.produce(
                topic=topic,
                key=key.encode('utf-8'),
                value=json.dumps(value).encode('utf-8'),
                on_delivery=self._delivery_callback,
            )
            # 이벤트 큐 처리 (non-blocking)
            self._producer.poll(0)
        except KafkaException as e:
            logger.error(f"[Producer] Kafka produce 실패 (topic={topic}, key={key}): {e}")

    def flush(self, timeout: float = 5.0) -> None:
        """버퍼에 남은 메시지를 모두 전송한다."""
        self._producer.flush(timeout=timeout)

    @staticmethod
    def _delivery_callback(err, msg) -> None:
        if err:
            logger.error(f"[Producer] 전송 실패: {err}, topic={msg.topic()}, key={msg.key()}")
        else:
            logger.debug(
                f"[Producer] 전송 성공: topic={msg.topic()}, partition={msg.partition()}, offset={msg.offset()}"
            )
