import os
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT=Path(__file__).resolve().parents[2]


class TopicConfigurationTest(unittest.TestCase):
    def run_script(self,replicas='3',minimum='2',actual='3',actual_min='2'):
        with tempfile.TemporaryDirectory() as folder:
            binary=Path(folder)
            topics=binary/'kafka-topics'
            topics.write_text('#!/usr/bin/env bash\nif [[ "$*" == *--describe* ]]; then echo "Topic: test PartitionCount: 3 ReplicationFactor: '+actual+' Configs:"; fi\n')
            topics.chmod(0o755)
            config=binary/'kafka-configs'
            config.write_text('#!/usr/bin/env bash\necho "min.insync.replicas='+actual_min+' unclean.leader.election.enable=false"\n')
            config.chmod(0o755)
            return subprocess.run(['bash',str(ROOT/'infra/kafka/topic-init.sh')],env={**os.environ,
                'PATH':str(binary)+':'+os.environ['PATH'],'KAFKA_REPLICATION_FACTOR':replicas,'KAFKA_MIN_INSYNC_REPLICAS':minimum},
                capture_output=True,text=True,timeout=10)

    def test_matching_existing_configuration_is_accepted(self):
        self.assertEqual(0,self.run_script().returncode)

    def test_existing_replica_mismatch_requires_explicit_reassignment(self):
        result=self.run_script(actual='1')
        self.assertNotEqual(0,result.returncode)
        self.assertIn('explicit reassignment',result.stderr)

    def test_minimum_isr_mismatch_is_not_silently_changed(self):
        self.assertNotEqual(0,self.run_script(actual_min='1').returncode)

    def test_invalid_requested_minimum_is_rejected(self):
        self.assertEqual(2,self.run_script(minimum='4').returncode)
