"""Local durable command and Ack journal; no ROS or Kafka dependencies."""
import datetime
import hashlib
import json
import os
import re
import sqlite3
import threading
import time
import uuid
from pathlib import Path


def expiry_time(value):
    value = re.sub(r'\.(\d+)(?=Z|[+-]\d{2}:\d{2}$)', lambda match: '.' + match[1][:6].ljust(6, '0'), value)
    return datetime.datetime.fromisoformat(value.replace('Z', '+00:00'))


def encoded(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), allow_nan=False)


class CapacityError(RuntimeError):
    pass


class DeliveryJournal:
    """Each connection serializes transactions; SQLite coordinates separate processes."""

    def __init__(self, path, maximum=10000, budget=1024**3):
        if maximum < 1 or budget < 1024**2:
            raise ValueError('Invalid journal limits')
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        self.path, self.maximum, self.budget = str(path), maximum, budget
        self.lock = threading.RLock()
        self.db = sqlite3.connect(path, timeout=1, check_same_thread=False)
        self.db.row_factory = sqlite3.Row
        self.db.execute('PRAGMA journal_mode=WAL')
        self.db.execute('PRAGMA synchronous=FULL')
        self.db.executescript('''
            CREATE TABLE IF NOT EXISTS inbox (
                cmd_id TEXT PRIMARY KEY, fleet TEXT NOT NULL, robot TEXT NOT NULL,
                payload TEXT NOT NULL, created REAL NOT NULL,
                state TEXT NOT NULL DEFAULT 'PENDING', reported INTEGER NOT NULL DEFAULT 0
            );
            CREATE TABLE IF NOT EXISTS execution (
                cmd_id TEXT PRIMARY KEY, fleet TEXT NOT NULL, robot TEXT NOT NULL,
                signature TEXT NOT NULL, sequence INTEGER NOT NULL, state TEXT NOT NULL,
                last_ack TEXT, runtime TEXT, unknown INTEGER NOT NULL DEFAULT 0
            );
            CREATE TABLE IF NOT EXISTS sequence (fleet TEXT, robot TEXT, highest INTEGER NOT NULL,
                PRIMARY KEY(fleet,robot));
            CREATE TABLE IF NOT EXISTS ack (
                event_id TEXT PRIMARY KEY, cmd_id TEXT NOT NULL, payload TEXT NOT NULL,
                created REAL NOT NULL
            );
        ''')

    def close(self):
        with self.lock:
            self.db.close()

    def disk_bytes(self):
        return sum(os.path.getsize(self.path + suffix) for suffix in ('', '-wal', '-shm')
                   if os.path.exists(self.path + suffix))

    def _capacity(self):
        unresolved = self.db.execute("""SELECT count(*) FROM (
            SELECT cmd_id FROM inbox WHERE state IN ('PENDING','UNKNOWN')
            UNION SELECT cmd_id FROM execution WHERE state IN ('INTENT','STARTED','UNKNOWN')
            UNION SELECT cmd_id FROM ack
        )""").fetchone()[0]
        # Reserve 64 KiB per unresolved command for subsequent results and interruption records.
        if unresolved >= self.maximum or self.disk_bytes() + (unresolved + 1) * 65536 > self.budget:
            raise CapacityError('Durable command journal capacity exceeded')

    def healthy(self):
        with self.lock:
            self._capacity()
            return True

    def enqueue(self, command):
        sequence = command.get('command_sequence')
        if isinstance(sequence, bool) or not isinstance(sequence, int) or not 1 <= sequence <= 2**63-1:
            raise ValueError('Invalid command sequence')
        expiry = expiry_time(command['expires_at'])
        if expiry.tzinfo is None:
            raise ValueError('Command expiry requires timezone')
        payload = encoded(command)
        if len(payload.encode()) > 16384:
            raise ValueError('Command payload exceeds 16 KiB')
        with self.lock, self.db:
            self.db.execute('BEGIN IMMEDIATE')
            old = self.db.execute('SELECT payload FROM inbox WHERE cmd_id=?', (command['cmd_id'],)).fetchone()
            if old:
                if old['payload'] != payload:
                    raise ValueError('cmd_id reused with different payload')
                return
            self._capacity()
            self.db.execute('INSERT INTO inbox(cmd_id,fleet,robot,payload,created) VALUES (?,?,?,?,?)',
                            (command['cmd_id'], command['fleet_id'], command['robot_id'], payload, time.time()))

    def pending(self, limit=100):
        with self.lock:
            return [json.loads(row[0]) for row in self.db.execute(
                "SELECT payload FROM inbox WHERE state='PENDING' ORDER BY created LIMIT ?", (limit,))]

    def reject_inbox(self, cmd_id):
        with self.lock, self.db:
            self.db.execute("UPDATE inbox SET state='UNKNOWN',reported=0 WHERE cmd_id=? AND state='PENDING'", (cmd_id,))

    def receipts(self, limit=100):
        with self.lock:
            return [dict(cmdId=row['cmd_id'], robotId=row['robot'], outcomeUnknown=bool(row['uncertain']))
                    for row in self.db.execute('''SELECT i.cmd_id,i.robot,
                        (i.state='UNKNOWN' OR COALESCE(e.unknown,0)=1) AS uncertain
                        FROM inbox i LEFT JOIN execution e ON e.cmd_id=i.cmd_id
                        WHERE i.reported=0 AND (i.state='UNKNOWN' OR e.cmd_id IS NOT NULL) LIMIT ?''', (limit,))]

    def reported(self, receipts):
        with self.lock, self.db:
            for receipt in receipts:
                # A concurrent interruption must not be hidden by a preceding receipt.
                self.db.execute('''UPDATE inbox SET reported=1,state=CASE WHEN state='PENDING' THEN 'RECEIVED' ELSE state END
                    WHERE cmd_id=? AND (?=1 OR NOT EXISTS(SELECT 1 FROM execution e WHERE e.cmd_id=inbox.cmd_id AND e.unknown=1))''',
                    (receipt['cmdId'], int(receipt['outcomeUnknown'])))

    def recover(self, fleet, robot):
        with self.lock, self.db:
            self.db.execute("UPDATE inbox SET reported=0 WHERE cmd_id IN (SELECT cmd_id FROM execution WHERE fleet=? AND robot=? AND state IN ('INTENT','STARTED'))", (fleet, robot))
            self.db.execute("UPDATE execution SET state='UNKNOWN',unknown=1 WHERE fleet=? AND robot=? AND state IN ('INTENT','STARTED')", (fleet, robot))

    def begin(self, command, now=None):
        """Commit execution intent before returning permission to change robot state."""
        signature = hashlib.sha256(encoded(command).encode()).hexdigest()
        with self.lock, self.db:
            # BEGIN IMMEDIATE avoids two robot processes both passing the deduplication read.
            self.db.execute('BEGIN IMMEDIATE')
            old = self.db.execute('SELECT * FROM execution WHERE cmd_id=?', (command['cmd_id'],)).fetchone()
            if old:
                if old['signature'] != signature:
                    raise ValueError('cmd_id reused with different payload')
                return False, json.loads(old['last_ack']) if old['last_ack'] else None
            if not self.db.execute('SELECT 1 FROM inbox WHERE cmd_id=?', (command['cmd_id'],)).fetchone():
                self._capacity()
            sequence = command.get('command_sequence')
            if isinstance(sequence, bool) or not isinstance(sequence, int) or sequence < 1 or sequence > 2**63-1:
                raise ValueError('Invalid command sequence')
            expiry = expiry_time(command['expires_at'])
            if expiry.tzinfo is None:
                raise ValueError('Command expiry requires timezone')
            highest = self.db.execute('SELECT highest FROM sequence WHERE fleet=? AND robot=?',
                                      (command['fleet_id'], command['robot_id'])).fetchone()
            checked_at = time.time() if now is None else now
            reason = 'Command expired before execution' if expiry.timestamp() <= checked_at else (
                'Command superseded by a newer sequence' if highest and sequence <= highest[0] else None)
            self.db.execute('''INSERT INTO sequence VALUES (?,?,?) ON CONFLICT(fleet,robot)
                DO UPDATE SET highest=MAX(highest,excluded.highest)''',
                (command['fleet_id'], command['robot_id'], sequence))
            self.db.execute('INSERT INTO execution(cmd_id,fleet,robot,signature,sequence,state,runtime) VALUES (?,?,?,?,?,?,?)',
                            (command['cmd_id'], command['fleet_id'], command['robot_id'], signature, sequence,
                             'INTENT', command.get('simulation_runtime_id')))
            if reason:
                ack = self._ack(command['cmd_id'], 'failed', reason)
                self._record(ack)
                return False, ack
            return True, None

    def execute(self, command, apply):
        """Recheck the deadline after the intent commit, immediately before applying the action."""
        allowed, cached = self.begin(command)
        if not allowed:
            return [cached] if cached else []
        if expiry_time(command['expires_at']).timestamp() <= time.time():
            from .commands import CommandAck
            return self.record([CommandAck(command['cmd_id'], 'failed', 'Command expired before execution')])
        return self.record(apply())

    def _ack(self, cmd_id, status, message, data=None):
        row = self.db.execute('SELECT fleet,robot FROM execution WHERE cmd_id=?', (cmd_id,)).fetchone()
        return dict(event_id=str(uuid.uuid4()), cmd_id=cmd_id, fleet_id=row['fleet'], robot_id=row['robot'],
                    status=status, message=message, timestamp=datetime.datetime.now(datetime.timezone.utc).isoformat(), data=data or {})

    def _record(self, ack):
        payload = encoded(ack)
        self.db.execute('INSERT OR IGNORE INTO ack VALUES (?,?,?,?)', (ack['event_id'], ack['cmd_id'], payload, time.time()))
        self.db.execute('UPDATE execution SET last_ack=?,state=? WHERE cmd_id=?',
                        (payload, 'STARTED' if ack['status'] == 'accepted' else 'DONE', ack['cmd_id']))

    def record(self, results, data=None):
        with self.lock, self.db:
            acks = [self._ack(result.cmd_id, result.status, result.message, data) for result in results]
            for ack in acks:
                self._record(ack)
            return acks

    def acks(self, limit=100):
        with self.lock:
            return [json.loads(row[0]) for row in self.db.execute('SELECT payload FROM ack ORDER BY created LIMIT ?', (limit,))]

    def confirmed(self, event_ids):
        with self.lock, self.db:
            self.db.executemany('DELETE FROM ack WHERE event_id=?', ((event,) for event in event_ids))
        # command identity and cached result stay in execution for duplicate suppression.

    def stats(self):
        with self.lock:
            row = self.db.execute('SELECT count(*),COALESCE(MIN(created),?) FROM ack', (time.time(),)).fetchone()
            return dict(unconfirmedAcks=row[0], oldestAckAgeSeconds=max(0,time.time()-row[1]), diskBytes=self.disk_bytes())


def journal_from_env():
    if os.environ.get('COMMAND_DURABLE_ENABLED', 'false').lower() != 'true':
        return None
    path = os.environ.get('COMMAND_JOURNAL_PATH', '/var/lib/robot-delivery/journal.sqlite3')
    return DeliveryJournal(path, int(os.environ.get('COMMAND_JOURNAL_MAX_PENDING', '10000')),
                           int(os.environ.get('COMMAND_JOURNAL_BUDGET_BYTES', str(1024**3))))
