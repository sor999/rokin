import datetime
import tempfile
import unittest
import uuid
from unittest.mock import Mock

from fake_robot.commands import CommandAck
from fake_robot.durability import DeliveryJournal
from ros_kafka_bridge.durable_delivery import DurableBridgeDelivery


class DurableBridgeTest(unittest.TestCase):
    def test_broker_delivery_never_cleans_ack_before_database_confirmation(self):
        with tempfile.TemporaryDirectory() as temp:
            journal = DeliveryJournal(temp + '/journal.sqlite3')
            cmd = dict(cmd_id=str(uuid.uuid4()), fleet_id='default', robot_id='robot_1', command='stop', data={},
                       command_sequence=1, expires_at=(datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(seconds=5)).isoformat())
            journal.enqueue(cmd)
            journal.begin(cmd)
            ack = journal.record([CommandAck(cmd['cmd_id'], 'done', 'stopped')])[0]
            delivery = DurableBridgeDelivery.__new__(DurableBridgeDelivery)
            delivery.journal = journal
            delivery.producer = Mock()
            delivery.logger = Mock()
            delivery.storage_error = False
            delivery.storage_failures = delivery.retransmissions = 0
            delivery.stopping = Mock()
            delivery.stopping.is_set.side_effect = [False, True]
            delivery.request = Mock(side_effect=lambda path, body: {'eventIds': []} if path == 'ack-receipts' else None)
            delivery.sync()
            self.assertEqual([ack], journal.acks())
            delivery.producer.produce.assert_called_once()
            delivery.stopping.is_set.side_effect = [False, True]
            delivery.request = Mock(side_effect=lambda path, body: {'eventIds': [ack['event_id'], 'unrequested']} if path == 'ack-receipts' else None)
            delivery.sync()
            self.assertEqual([], journal.acks())
            self.assertFalse(journal.begin(cmd)[0])
            journal.close()

    def test_http_failure_keeps_ack_and_receipt_for_retry(self):
        with tempfile.TemporaryDirectory() as temp:
            journal = DeliveryJournal(temp + '/journal.sqlite3')
            cmd = dict(cmd_id=str(uuid.uuid4()), fleet_id='default', robot_id='robot_1', command='stop', data={},
                       command_sequence=1, expires_at='2099-01-01T00:00:00.123456789Z')
            journal.enqueue(cmd); journal.begin(cmd)
            journal.record([CommandAck(cmd['cmd_id'], 'done', 'stopped')])
            delivery = DurableBridgeDelivery.__new__(DurableBridgeDelivery)
            delivery.journal = journal; delivery.producer = Mock(); delivery.logger = Mock()
            delivery.stopping = Mock(); delivery.stopping.is_set.side_effect = [False, True]
            delivery.request = Mock(side_effect=OSError('offline'))
            delivery.sync()
            self.assertEqual(1, len(journal.acks()))
            self.assertEqual(1, len(journal.receipts()))
            journal.close()
