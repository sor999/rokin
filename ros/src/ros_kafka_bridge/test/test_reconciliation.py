import unittest
from unittest.mock import Mock
from ros_kafka_bridge.reconciliation import SimulationReconciler


def robot(rid='robot_4', version=1, desired='RUNNING'):
    return {'robotId': rid, 'version': version, 'desiredState': desired, 'startX': 0, 'startY': 0, 'speed': 1}


class SimulationReconcilerTest(unittest.TestCase):
    def setUp(self):
        self.start, self.stop, self.ready = Mock(), Mock(), Mock(return_value=True)
        self.manager = SimulationReconciler(self.start, self.stop, self.ready)

    def test_repeated_snapshot_does_not_restart_existing_robots(self):
        self.manager.reconcile([robot()])
        reports = self.manager.reconcile([robot(), robot('robot_5')])
        self.assertEqual(2, self.start.call_count)
        self.stop.assert_not_called()
        self.assertTrue(all(item['state'] == 'RUNNING' for item in reports))

    def test_delete_does_not_touch_other_robots(self):
        self.manager.reconcile([robot(), robot('robot_5')])
        reports = self.manager.reconcile([robot(version=2, desired='DELETED'), robot('robot_5')])
        self.stop.assert_called_once_with('robot_4')
        self.assertEqual('DELETED', reports[0]['state'])
        self.assertEqual({'robot_5': 1}, self.manager.active)

    def test_start_failure_is_reported_and_retried(self):
        self.start.side_effect = [RuntimeError('fixture'), None]
        self.assertEqual('FAILED', self.manager.reconcile([robot()])[0]['state'])
        self.assertEqual('RUNNING', self.manager.reconcile([robot()])[0]['state'])

    def test_invalid_snapshot_preserves_current_robots(self):
        self.manager.reconcile([robot()])
        with self.assertRaises(ValueError):
            self.manager.reconcile([robot('bad-id')])
        self.stop.assert_not_called()
        self.assertEqual({'robot_4': 1}, self.manager.active)

    def test_restored_manager_starts_saved_robots_without_movement_state(self):
        self.manager.reconcile([robot()])
        restored = SimulationReconciler(self.start, self.stop, self.ready)
        restored.reconcile([robot()])
        self.assertEqual(2, self.start.call_count)

    def test_not_ready_is_not_reported_as_running(self):
        self.ready.return_value = False
        self.assertEqual('STARTING', self.manager.reconcile([robot()])[0]['state'])

    def test_readiness_timeout_is_visible_and_can_recover(self):
        clock = Mock(return_value=0)
        self.ready.return_value = False
        manager = SimulationReconciler(self.start, self.stop, self.ready, clock=clock)
        self.assertEqual('STARTING', manager.reconcile([robot()])[0]['state'])
        clock.return_value = 31
        self.assertEqual('FAILED', manager.reconcile([robot()])[0]['state'])
        self.ready.return_value = True
        self.assertEqual('RUNNING', manager.reconcile([robot()])[0]['state'])
        self.start.assert_called_once()
