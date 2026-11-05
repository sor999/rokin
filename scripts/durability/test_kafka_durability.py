"""Explicitly opt-in: failures and record truncation only in rokin-durable-lab test topics."""
import datetime
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import time
import unittest
import uuid

from fake_robot.commands import CommandAck
from fake_robot.durability import DeliveryJournal

ROOT=Path(__file__).resolve().parents[2]
COMPOSE=['docker','compose','--project-name','rokin-durable-lab','-f',str(ROOT/'infra/durability/compose.yml')]


@unittest.skipUnless(os.environ.get('RUN_KAFKA_DURABILITY')=='1','requires isolated three-broker lab')
class KafkaDurabilityTest(unittest.TestCase):
    def cli(self,command,*args,input=None,timeout=30):
        return subprocess.run(COMPOSE+['exec','-T','kafka2',command,*args],input=input,text=True,capture_output=True,timeout=timeout)

    def check(self,command,*args,input=None,timeout=30):
        result=self.cli(command,*args,input=input,timeout=timeout)
        self.assertEqual(0,result.returncode,result.stderr[-2000:])
        return result.stdout

    def wait_isr(self,topic,count=3,seconds=90):
        deadline=time.monotonic()+seconds
        while time.monotonic()<deadline:
            result=self.cli('kafka-topics','--bootstrap-server','kafka2:29092','--describe','--topic',topic,timeout=15)
            matches=re.findall(r'Isr:\s*([\d,]+)',result.stdout)
            if matches and all(len(value.split(','))>=count for value in matches):return
            time.sleep(1)
        self.fail('Replica convergence timed out: '+result.stdout[-1000:])

    def topic(self):
        topic='durability-test-'+uuid.uuid4().hex
        self.check('kafka-topics','--bootstrap-server','kafka2:29092','--create','--topic',topic,
                   '--replica-assignment','1:2:3','--config','min.insync.replicas=2',
                   '--config','unclean.leader.election.enable=false')
        self.wait_isr(topic)
        return topic

    def produce(self,topic,payload,expected=True):
        result=self.cli('kafka-console-producer','--bootstrap-server','kafka2:29092','--topic',topic,
                        '--producer-property','acks=all','--producer-property','enable.idempotence=true',
                        '--producer-property','delivery.timeout.ms=5000','--producer-property','request.timeout.ms=3000',
                        '--producer-property','max.block.ms=5000',input=json.dumps(payload)+'\n',timeout=25)
        success=result.returncode==0 and 'ERROR' not in result.stderr and 'Error when sending' not in result.stderr
        self.assertEqual(expected,success,(result.stdout+result.stderr)[-2500:])

    def consume(self,topic):
        result=self.cli('kafka-console-consumer','--bootstrap-server','kafka2:29092','--topic',topic,
                        '--partition','0','--offset','earliest','--max-messages','1','--timeout-ms','3000',timeout=20)
        return [json.loads(line) for line in result.stdout.splitlines() if line.startswith('{')]

    def control(self,action,*services):
        subprocess.run(COMPOSE+[action,*services],check=True,capture_output=True,timeout=60)

    def journal(self,path):
        journal=DeliveryJournal(path)
        cmd=dict(cmd_id=str(uuid.uuid4()),fleet_id='test',robot_id='test_robot',command='stop',data={},
                 command_sequence=1,expires_at=(datetime.datetime.now(datetime.timezone.utc)+datetime.timedelta(seconds=5)).isoformat())
        journal.enqueue(cmd);journal.begin(cmd)
        ack=journal.record([CommandAck(cmd['cmd_id'],'done','Stopped')])[0]
        return journal,ack

    def test_leader_failure_quorum_loss_and_recovery_preserve_local_ack(self):
        topic=self.topic()
        with tempfile.TemporaryDirectory() as temp:
            journal,ack=self.journal(temp+'/journal.sqlite3')
            try:
                self.produce(topic,{'before':True})
                self.control('stop','kafka1')
                self.wait_isr(topic,2)
                self.produce(topic,{'afterLeaderFailure':True})
                self.control('stop','kafka3')
                time.sleep(7)
                self.produce(topic,ack,expected=False)
                self.assertEqual([ack],journal.acks())
            finally:
                self.control('start','kafka1','kafka3')
            self.wait_isr(topic)
            self.produce(topic,ack)
            # Check the retry through a dedicated topic with no preceding records.
            retry_topic=self.topic()
            self.produce(retry_topic,journal.acks()[0])
            self.assertEqual([ack],self.consume(retry_topic))
            self.assertEqual([ack],journal.acks())  # Broker receipt never authorizes local deletion.
            journal.close()

    def test_ack_replay_after_original_kafka_record_is_removed(self):
        topic=self.topic()
        self.assertTrue(topic.startswith('durability-test-'))
        with tempfile.TemporaryDirectory() as temp:
            journal,ack=self.journal(temp+'/journal.sqlite3')
            self.produce(topic,ack)
            self.assertEqual([ack],self.consume(topic))
            # Only this newly-created test topic is truncated. Shared application topics are never deleted.
            offsets=json.dumps({'partitions':[{'topic':topic,'partition':0,'offset':-1}],'version':1})
            self.check('bash','-c','file=$(mktemp); cat > "$file"; kafka-delete-records --bootstrap-server kafka2:29092 --offset-json-file "$file"; result=$?; rm -f "$file"; exit "$result"',input=offsets)
            self.assertEqual([],self.consume(topic))
            self.assertEqual([ack],journal.acks())
            self.produce(topic,journal.acks()[0])
            self.assertEqual([ack],self.consume(topic))
            journal.close()

if __name__=='__main__':unittest.main()
