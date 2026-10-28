import base64
import json
import unittest
from unittest.mock import Mock
from confluent_kafka import TopicPartition

from ros_kafka_bridge.commands import CommandRejected
from ros_kafka_bridge.consumer import KafkaCommandConsumer


def message(payload=b'{"valid":true}', offset=7):
    value = Mock()
    value.value.return_value = payload
    value.key.return_value = b'robot_1'
    value.topic.return_value = 'cmd.robot'
    value.partition.return_value = 1
    value.offset.return_value = offset
    value.error.return_value = None
    return value


class ConsumerTest(unittest.TestCase):
    def setUp(self):
        self.events = []
        self.client = Mock()
        self.client.commit.side_effect = lambda **kw: self.events.append(('commit', kw['message'].offset())) or []
        self.producer = Mock()
        self.delivery = None

        def produce(*args, **kwargs):
            self.delivery = kwargs['on_delivery']
            self.events.append(('dlq', json.loads(kwargs['value'])))

        def flush(**kwargs):
            self.delivery(None, Mock())
            self.events.append(('delivered',))
            return 0

        self.producer.produce.side_effect = produce
        self.producer.flush.side_effect = flush
        self.callback = Mock()
        self.consumer = KafkaCommandConsumer('unused', 'bridge.site-a', 'cmd.robot', self.callback,
                                             consumer_factory=lambda config: self.client,
                                             producer_factory=lambda config: self.producer,
                                             retry_seconds=0)

    def test_malformed_payload_is_preserved_and_committed_after_dlq_ack(self):
        raw = b'\xffbad-json'
        record = message(raw)
        self.consumer._process(self.client, self.producer, record)
        self.callback.assert_not_called()
        payload = self.events[0][1]
        self.assertEqual(raw, base64.b64decode(payload['original_payload_base64']))
        self.assertEqual(('cmd.robot', 1, 7), (payload['source_topic'], payload['source_partition'], payload['source_offset']))
        self.assertEqual('delivered', self.events[1][0])
        self.assertEqual(('commit', 7), self.events[2])
        self.client.commit.assert_called_once_with(message=record, asynchronous=False)

    def test_ownership_error_uses_dlq(self):
        self.callback.side_effect = CommandRejected('Unowned robot')
        self.consumer._process(self.client, self.producer, message())
        self.assertEqual('Unowned robot', self.events[0][1]['error'])
        self.assertEqual(('commit', 7), self.events[-1])

    def test_dlq_failure_does_not_commit_source(self):
        for failure in ('timeout', 'delivery_error', 'queue_full'):
            with self.subTest(failure=failure):
                self.client.commit.reset_mock()
                if failure == 'timeout':
                    self.producer.flush.side_effect = lambda **kwargs: 1
                elif failure == 'delivery_error':
                    self.producer.flush.side_effect = lambda **kwargs: self.delivery(RuntimeError('broker down'), Mock()) or 0
                else:
                    self.producer.produce.side_effect = BufferError('queue full')
                with self.assertRaises((RuntimeError, BufferError)):
                    self.consumer._process(self.client, self.producer, message(b'{'))
                self.client.commit.assert_not_called()

    def test_ros_failure_does_not_commit_or_dlq(self):
        self.callback.side_effect = RuntimeError('ROS publisher failed')
        with self.assertRaises(RuntimeError):
            self.consumer._process(self.client, self.producer, message())
        self.client.commit.assert_not_called()
        self.producer.produce.assert_not_called()

    def test_commit_errors_are_reported(self):
        self.client.commit.return_value = [Mock(error=RuntimeError('lost partition'))]
        self.client.commit.side_effect = None
        with self.assertRaises(RuntimeError):
            self.consumer._process(self.client, self.producer, message())

    def test_retry_finishes_failed_record_before_reading_next_offset(self):
        records = [message(offset=7), message(offset=8)]
        attempts = []

        def poll(**kwargs):
            if records:
                return records.pop(0)
            self.consumer._stop.set()
            return None

        def callback(value):
            attempts.append(len(attempts))
            self.events.append(('attempt', len(attempts)))
            if len(attempts) == 1:
                raise RuntimeError('temporary ROS failure')

        self.callback.side_effect = callback
        self.client.poll.side_effect = poll
        with self.assertLogs('ros_kafka_bridge.consumer', level='ERROR'):
            self.consumer._poll_loop()
        self.assertEqual([('attempt', 1), ('attempt', 2), ('commit', 7), ('attempt', 3), ('commit', 8)], self.events)
        self.client.close.assert_called_once()

    def test_shutdown_during_failure_leaves_offset_uncommitted(self):
        self.client.poll.return_value = message()

        def fail(value):
            self.consumer._stop.set()
            raise RuntimeError('shutdown')

        self.callback.side_effect = fail
        with self.assertLogs('ros_kafka_bridge.consumer', level='ERROR'):
            self.consumer._poll_loop()
        self.client.commit.assert_not_called()
        self.client.close.assert_called_once()

    def test_first_assignment_commits_baseline_before_accepting_commands(self):
        partition = TopicPartition('cmd.robot', 0, -1001)
        self.client.committed.return_value = [partition]
        self.client.get_watermark_offsets.return_value = (0, 10)
        self.client.commit.side_effect = None
        self.client.commit.return_value = []
        self.consumer._on_assign(self.client, [partition])
        self.assertTrue(self.consumer.ready.is_set())
        self.assertEqual(10, partition.offset)
        self.client.commit.assert_called_once_with(offsets=[partition], asynchronous=False)

    def test_existing_assignment_resumes_its_committed_offset(self):
        partition = TopicPartition('cmd.robot', 0, 7)
        self.client.committed.return_value = [partition]
        self.consumer._on_assign(self.client, [partition])
        self.assertTrue(self.consumer.ready.is_set())
        self.assertEqual(7, partition.offset)
        self.client.commit.assert_not_called()
        self.client.get_watermark_offsets.assert_not_called()

    def test_failed_baseline_commit_does_not_mark_consumer_ready(self):
        partition = TopicPartition('cmd.robot', 0, -1001)
        self.client.committed.return_value = [partition]
        self.client.get_watermark_offsets.return_value = (0, 10)
        self.client.commit.side_effect = RuntimeError('broker disconnected')
        with self.assertRaises(RuntimeError):
            self.consumer._on_assign(self.client, [partition])
        self.assertFalse(self.consumer.ready.is_set())

    def test_excessively_nested_json_is_dlq_not_retried_forever(self):
        self.callback.side_effect = CommandRejected('Command must be a JSON object')
        self.consumer._process(self.client, self.producer, message(b'[' * 2000 + b'0' + b']' * 2000))
        self.assertEqual('dlq', self.events[0][0])
        self.assertEqual(('commit', 7), self.events[-1])
