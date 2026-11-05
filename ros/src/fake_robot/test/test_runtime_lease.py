import unittest
from fake_robot.runtime_lease import RuntimeLease, topic_prefix

RUNTIME='00000000-0000-4000-8000-000000000001'


class RuntimeLeaseTest(unittest.TestCase):
    def setUp(self):
        self.now=100.0
        self.lease=RuntimeLease(RUNTIME,clock=lambda:self.now)

    def response(self,epoch=1,ttl=12000,**overrides):
        return dict(fleetId='default',runtimeId=RUNTIME,epoch=epoch,ttlMillis=ttl,
                    granted=True,retired=False,**overrides)

    def test_request_start_bounds_delayed_response(self):
        self.now=105
        self.assertTrue(self.lease.accept(self.response(),100,'default'))
        self.now=109.9
        self.assertTrue(self.lease.active())
        self.now=110
        self.assertFalse(self.lease.active())
        self.assertTrue(self.lease.retired)
        self.assertFalse(self.lease.accept(self.response(),110,'default'))

    def test_late_first_response_does_not_activate(self):
        self.now=111
        self.assertFalse(self.lease.accept(self.response(),100,'default'))
        self.assertFalse(self.lease.active())

    def test_renewal_before_deadline_and_failure_without_extension(self):
        self.assertTrue(self.lease.accept(self.response(),100,'default'))
        self.now=108
        self.assertTrue(self.lease.accept(self.response(),108,'default'))
        self.now=117
        self.assertTrue(self.lease.active())
        self.now=118
        self.assertFalse(self.lease.active())

    def test_standby_rejection_and_token_change(self):
        response=self.response();response['granted']=False;response['ttlMillis']=0
        self.assertFalse(self.lease.accept(response,100,'default'))
        self.assertFalse(self.lease.retired)
        self.assertTrue(self.lease.accept(self.response(),100,'default'))
        self.assertFalse(self.lease.accept(self.response(epoch=2),100,'default'))
        self.assertTrue(self.lease.retired)

    def test_explicit_ownership_denial_retires_active_owner(self):
        self.lease.accept(self.response(),100,'default')
        denied=self.response();denied['granted']=False
        self.assertFalse(self.lease.accept(denied,100,'default'))
        self.assertFalse(self.lease.active())

    def test_invalid_grant_and_namespace(self):
        for response in [self.response(epoch=True),self.response(ttl=100),dict(self.response(),runtimeId='wrong'),dict(self.response(),granted='true')]:
            with self.assertRaises(ValueError):self.lease.accept(response,100,'default')
        self.assertNotEqual(topic_prefix('robot',RUNTIME),topic_prefix('robot',RUNTIME[:-1]+'2'))
        self.assertEqual('/fleet/robot',topic_prefix('robot'))


if __name__=='__main__':unittest.main()
