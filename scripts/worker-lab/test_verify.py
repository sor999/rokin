import unittest
from verify import metric_values


class MetricsParsingTest(unittest.TestCase):
    def test_only_exact_metric_samples(self):
        body = '# HELP worker_pending test\nworker_pending{lane="ack",kind="messages"} 12\nworker_pending{lane="telemetry",kind="reserved_bytes"} 2.1e+06\nworker_pending_other 999\n'
        self.assertEqual([12, 2100000], metric_values(body, 'worker_pending'))
        self.assertEqual([], metric_values(body, 'missing'))

    def test_process_metric_without_labels(self):
        self.assertEqual([1024], metric_values('process_resident_memory_bytes 1024\n', 'process_resident_memory_bytes'))


if __name__ == '__main__':
    unittest.main()
