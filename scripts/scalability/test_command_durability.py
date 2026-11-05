"""Opt-in checks against the isolated dual API lab, without deleting volumes."""
import json
import os
import subprocess
import time
import unittest
import urllib.error
import uuid

from lab_lock import lock
from measure import Client, prepare, request, verify_lab


@unittest.skipUnless(os.environ.get('RUN_COMMAND_DURABILITY') == '1', 'requires durable isolated lab')
class CommandDurabilityIntegrationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.guard = lock(); cls.guard.__enter__()
        try:
            verify_lab('dual')
            cls.robots = prepare(3)
            value = subprocess.check_output(['docker','inspect','--format','{{range .Config.Env}}{{println .}}{{end}}','rokin-scale-lab-api-1'], text=True)
            if 'COMMAND_DURABLE_ENABLED=true' not in value.splitlines():
                raise RuntimeError('Restart lab with COMMAND_DURABLE_ENABLED=true')
        except Exception:
            cls.guard.__exit__(None,None,None); raise

    @classmethod
    def tearDownClass(cls): cls.guard.__exit__(None,None,None)

    def setUp(self):
        prepare(3)
        self.client = Client(0,'http://localhost:18081',self.robots)
        self.client.start()
        self.wait(lambda:self.client.ws_ready.is_set(),30)

    def tearDown(self): self.client.stop.set()

    def wait(self,predicate,seconds=30):
        deadline=time.monotonic()+seconds
        while time.monotonic()<deadline:
            try:
                if predicate(): return
            except (urllib.error.URLError, ConnectionError):
                pass
            time.sleep(.1)
        self.fail('Durable delivery convergence timed out')

    def send(self,action='stop',data=None,key=None):
        key=key or str(uuid.uuid4())
        self.client.send('SEND',{'destination':'/app/command','content-type':'application/json'},json.dumps({
            'requestId':str(uuid.uuid4()),'idempotencyKey':key,'robotId':'robot_1','command':action,'data':data or {}}))
        return key

    def lookup(self,key):
        try:return request('http://localhost:18082/api/commands?idempotencyKey='+key)
        except urllib.error.HTTPError as error:
            if error.code==404:return None
            raise

    def docker(self,*args):
        subprocess.run(['docker',*args],check=True,stdout=subprocess.DEVNULL,timeout=60)

    def journal(self,cmd):
        code="import sqlite3,json; db=sqlite3.connect('/var/lib/robot-delivery/journal.sqlite3'); print(json.dumps(db.execute('SELECT state,unknown FROM execution WHERE cmd_id=?',("+repr(cmd)+",)).fetchone()))"
        return json.loads(subprocess.check_output(['docker','exec','rokin-scale-lab-fake_robot-1','python3','-c',code],text=True))

    def test_api_restart_preserves_accepted_command(self):
        key=self.send()
        self.wait(lambda:self.lookup(key) is not None)
        cmd=self.lookup(key)['cmdId']
        self.docker('restart','--time','5','rokin-scale-lab-api-1')
        self.wait(lambda:request('http://localhost:18081/api/commands/'+cmd).get('deliveryStatus')=='COMPLETED',60)
        self.assertEqual(cmd,self.lookup(key)['cmdId'])

    def test_invalid_request_key_returns_correlated_rejection(self):
        correlation=str(uuid.uuid4())
        self.client.send('SEND',{'destination':'/app/command','content-type':'application/json'},json.dumps({
            'requestId':correlation,'idempotencyKey':'invalid','robotId':'robot_1','command':'stop','data':{}}))
        self.wait(lambda:correlation in self.client.results)
        result=self.client.results[correlation][0]
        self.assertEqual('rejected',result['status']); self.assertEqual('INVALID_REQUEST',result['errorCode'])
        self.assertIsNone(result['cmdId'])

    def test_response_loss_and_duplicate_request_recover_same_command(self):
        key=self.send()
        self.wait(lambda:self.lookup(key) is not None)
        command=self.lookup(key)['cmdId']
        # Deliberately ignore the original STOMP acceptance and recover through the other API.
        self.wait(lambda:(self.lookup(key) or {}).get('deliveryStatus')=='COMPLETED')
        self.send(key=key)
        self.wait(lambda:self.lookup(key)['cmdId']==command)
        self.assertEqual('done',self.lookup(key)['ack']['status'])
        self.assertEqual('DONE',self.journal(command)[0])

    def test_worker_and_database_outage_retains_ack_until_recovery(self):
        self.docker('stop','--time','10','rokin-scale-lab-worker-1')
        try:
            key=self.send()
            self.wait(lambda:self.lookup(key) is not None)
            cmd=self.lookup(key)['cmdId']
            self.wait(lambda:self.journal(cmd)==['DONE',0])
            self.assertIsNone(self.lookup(key)['ack'])
            self.docker('stop','--time','5','rokin-scale-lab-postgres-1')
            time.sleep(3)
            self.assertEqual(['DONE',0],self.journal(cmd))
        finally:
            self.docker('start','rokin-scale-lab-postgres-1')
            self.wait(lambda:self.database_ready())
            self.docker('start','rokin-scale-lab-worker-1')
        self.wait(lambda:(self.lookup(key) or {}).get('deliveryStatus')=='COMPLETED',60)
        self.assertEqual('done',self.lookup(key)['ack']['status'])

    def database_ready(self):
        return subprocess.run(['docker','exec','rokin-scale-lab-postgres-1','pg_isready','-U','postgres'],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL).returncode==0

    def test_ros_crash_does_not_resume_started_move(self):
        key=self.send('move_to',{'x':1000,'y':0})
        self.wait(lambda:self.lookup(key) is not None)
        cmd=self.lookup(key)['cmdId']
        self.wait(lambda:self.journal(cmd)==['STARTED',0])
        self.docker('kill','--signal','KILL','rokin-scale-lab-fake_robot-1')
        self.docker('start','rokin-scale-lab-fake_robot-1')
        self.wait(lambda:self.journal(cmd)==['UNKNOWN',1],90)
        self.wait(lambda:(self.lookup(key) or {}).get('deliveryStatus')=='OUTCOME_UNKNOWN',30)
        time.sleep(2)
        robot=next(r for r in request('http://localhost:18082/api/robots') if r['robotId']=='robot_1')
        self.assertLess(abs(robot['pose']['x']),1)
