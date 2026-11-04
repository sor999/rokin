import datetime
import os
import subprocess
import sys
import tempfile
import unittest
import uuid
import threading
from unittest.mock import Mock, patch

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

    def test_lock_wait_is_included_in_deadline_check(self):
        cmd = self.command(action='stop')
        cmd['expires_at'] = '1970-01-01T00:02:30+00:00'
        entered, release = threading.Event(), threading.Event()
        original_lock = self.journal.lock
        results = []
        class GatedLock:
            def __enter__(self):
                original_lock.acquire()
                entered.set()
                if not release.wait(2):
                    original_lock.release()
                    raise RuntimeError('Test gate timed out')
            def __exit__(self, *args):
                original_lock.release()
        self.journal.lock = GatedLock()
        worker = threading.Thread(target=lambda: results.append(self.journal.begin(cmd)))
        try:
            with patch('fake_robot.durability.time.time', return_value=100) as clock:
                worker.start()
                self.assertTrue(entered.wait(1))
                clock.return_value = 200
                release.set()
                worker.join(2)
                self.assertFalse(worker.is_alive())
            self.assertFalse(results[0][0])
            self.assertEqual('failed', results[0][1]['status'])
        finally:
            release.set()
            worker.join(2)
            self.journal.lock = original_lock

    def test_commit_delay_cannot_execute_an_expired_stop(self):
        cmd = self.command(action='stop')
        cmd['expires_at'] = '1970-01-01T00:02:30+00:00'
        original_begin = self.journal.begin
        apply = Mock(return_value=[CommandAck(cmd['cmd_id'], 'done', 'stopped')])
        with patch('fake_robot.durability.time.time', return_value=100) as clock:
            def slow_commit(command):
                result = original_begin(command)
                clock.return_value = 200
                return result
            with patch.object(self.journal, 'begin', side_effect=slow_commit):
                result = self.journal.execute(cmd, apply)
        apply.assert_not_called()
        self.assertEqual('failed', result[0]['status'])
        self.assertEqual(result, self.journal.acks())

    def test_execute_replays_result_without_applying_a_second_time(self):
        cmd = self.command(action='stop')
        apply = Mock(return_value=[CommandAck(cmd['cmd_id'], 'done', 'stopped')])
        first = self.journal.execute(cmd, apply)
        self.assertEqual(first, self.journal.execute(cmd, apply))
        apply.assert_called_once()

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
