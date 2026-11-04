import datetime
import os
import subprocess
import sys
import tempfile
import unittest
import uuid

from fake_robot.durability import DeliveryJournal, CapacityError
from fake_robot.commands import CommandAck, RobotCommands
from fake_robot.logic import FakeRobotLogic


class DeliveryJournalTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = self.temp.name + '/journal.sqlite3'
        self.journal = DeliveryJournal(self.path)

    def tearDown(self):
        self.journal.close()
        self.temp.cleanup()

    def command(self, seq=1, expiry=10, action='move_to'):
        return dict(cmd_id=str(uuid.uuid4()), fleet_id='default', robot_id='robot_1',
                    command=action, data={'x': 5, 'y': 0} if action == 'move_to' else {},
                    command_sequence=seq,
                    expires_at=(datetime.datetime.now(datetime.timezone.utc) + datetime.timedelta(seconds=expiry)).isoformat())

    def test_intent_survives_process_exit_without_reexecution(self):
        cmd = self.command()
        self.journal.enqueue(cmd)
        import json
        code = 'from fake_robot.durability import DeliveryJournal; import json,os; j=DeliveryJournal(os.environ["JOURNAL"]); j.begin(json.loads(os.environ["COMMAND"])); os._exit(9)'
        result = subprocess.run([sys.executable, '-c', code], env={**os.environ, 'JOURNAL': self.path, 'COMMAND': json.dumps(cmd)})
        self.assertEqual(9, result.returncode)
        self.journal.recover('default', 'robot_1')
        apply, ack = self.journal.begin(cmd)
        self.assertFalse(apply)
        self.assertIsNone(ack)
        self.assertTrue(self.journal.receipts()[0]['outcomeUnknown'])

    def test_ack_retained_until_database_confirmation_and_cached_after_cleanup(self):
        cmd = self.command()
        self.journal.enqueue(cmd)
        self.assertTrue(self.journal.begin(cmd)[0])
        generated = self.journal.record([CommandAck(cmd['cmd_id'], 'accepted', 'started')])[0]
        self.assertEqual(generated, self.journal.acks()[0])
        self.journal.reported(self.journal.receipts())
        self.assertEqual([], self.journal.pending())
        self.journal.confirmed([generated['event_id']])
        self.assertEqual([], self.journal.acks())
        self.assertEqual((False, generated), self.journal.begin(cmd))

    def test_expired_and_old_sequence_never_apply(self):
        expired = self.command(expiry=-1)
        apply, ack = self.journal.begin(expired)
        self.assertFalse(apply)
        self.assertEqual('failed', ack['status'])
        current = self.command(seq=3)
        self.assertTrue(self.journal.begin(current)[0])
        delayed_stop = self.command(seq=2, action='stop')
        self.assertFalse(self.journal.begin(delayed_stop)[0])
        self.assertIn('superseded', self.journal.begin(delayed_stop)[1]['message'])

    def test_duplicate_content_conflict_and_capacity(self):
        cmd = self.command()
        self.journal.enqueue(cmd)
        self.journal.enqueue(cmd)
        changed = {**cmd, 'data': {'x': 99, 'y': 0}}
        with self.assertRaises(ValueError): self.journal.enqueue(changed)
        self.journal.maximum = 1
        with self.assertRaises(CapacityError): self.journal.enqueue(self.command(seq=2))
        # Capacity must not prevent already admitted commands from completing.
        self.assertTrue(self.journal.begin(cmd)[0])
        self.journal.record([CommandAck(cmd['cmd_id'], 'done', 'done')])

    def test_recovery_report_race_does_not_hide_uncertain_execution(self):
        cmd = self.command()
        self.journal.enqueue(cmd)
        self.journal.begin(cmd)
        old_receipts = self.journal.receipts()
        self.journal.recover('default', 'robot_1')
        self.journal.reported(old_receipts)
        self.assertTrue(self.journal.receipts()[0]['outcomeUnknown'])

    def test_newer_move_finishes_interrupted_move(self):
        robot = RobotCommands(FakeRobotLogic())
        first, second = self.command(), self.command(seq=2)
        for cmd in (first, second):
            self.journal.enqueue(cmd)
            self.assertTrue(self.journal.begin(cmd)[0])
            self.journal.record(robot.handle(cmd['cmd_id'], cmd['command'], cmd['data']))
        statuses = [(ack['cmd_id'], ack['status']) for ack in self.journal.acks()]
        self.assertIn((first['cmd_id'], 'failed'), statuses)
        self.assertIn((second['cmd_id'], 'accepted'), statuses)

if __name__ == '__main__': unittest.main()
