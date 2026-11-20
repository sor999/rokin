import importlib.util
import json
from pathlib import Path
import sys
from types import SimpleNamespace
import unittest
import uuid

sys.path.insert(0,str(Path(__file__).resolve().parents[2]/'fake_robot'))
from fake_robot.runtime_lease import RuntimeLease
from ros_kafka_bridge.commands import ManagedFleetCommandRouter, CommandRejected


class LeaseRoutingTest(unittest.TestCase):
    def test_epoch_group_and_expired_router_cannot_relay(self):
        now=[0.0];rid=str(uuid.uuid4());lease=RuntimeLease(rid,clock=lambda:now[0])
        lease.accept(dict(fleetId='default',runtimeId=rid,epoch=7,granted=True,retired=False,ttlMillis=12000),0,'default')
        delivered=[];router=ManagedFleetCommandRouter('default',delivered.append,rid,lease)
        router.add('robot_1',1)
        cmd=dict(fleet_id='default',robot_id='robot_1',cmd_id=str(uuid.uuid4()),command='stop',data={},
                 simulation_version=1,simulation_runtime_id=rid,simulation_epoch=7)
        self.assertTrue(router.consumer_group('bridge').endswith('.managed.lease.7'))
        router.dispatch(cmd);self.assertEqual(1,len(delivered))
        with self.assertRaises(CommandRejected):router.dispatch(dict(cmd,simulation_epoch=6))
        now[0]=10
        with self.assertRaises(RuntimeError):router.dispatch(cmd)
        self.assertEqual(1,len(delivered))

    def test_boolean_epoch_is_not_fencing_token_one(self):
        rid=str(uuid.uuid4());lease=RuntimeLease(rid,clock=lambda:0)
        lease.accept(dict(fleetId='default',runtimeId=rid,epoch=1,granted=True,retired=False,ttlMillis=12000),0,'default')
        router=ManagedFleetCommandRouter('default',lambda _:self.fail('invalid token relayed'),rid,lease)
        router.add('robot_1',1)
        with self.assertRaises(CommandRejected):
            router.dispatch(dict(fleet_id='default',robot_id='robot_1',cmd_id=str(uuid.uuid4()),command='stop',data={},
                                 simulation_version=1,simulation_runtime_id=rid,simulation_epoch=True))


@unittest.skipUnless(importlib.util.find_spec('rclpy'),'requires ROS2')
class RobotLeaseTest(unittest.TestCase):
    def test_expired_node_stops_and_runtime_topics_are_isolated(self):
        import rclpy
        from rclpy.parameter import Parameter
        from fake_robot.main_node import FakeRobotNode
        rclpy.init()
        now=[0.0];rid=str(uuid.uuid4());lease=RuntimeLease(rid,clock=lambda:now[0])
        lease.accept(dict(fleetId='default',runtimeId=rid,epoch=7,granted=True,retired=False,ttlMillis=12000),0,'default')
        node=FakeRobotNode(lease=lease,use_global_arguments=False,parameter_overrides=[Parameter('simulation_runtime_id',value=rid)])
        other=None
        try:
            command=dict(fleet_id='default',robot_id='robot_1',cmd_id=str(uuid.uuid4()),command='move_to',data=dict(x=100,y=0),
                         simulation_runtime_id=rid,simulation_epoch=7)
            node.cmd_callback(SimpleNamespace(data=json.dumps(command)))
            node.timer_callback();before=node.logic.get_pose();self.assertGreater(before[0],0)
            now[0]=11;node.timer_callback();self.assertEqual(before,node.logic.get_pose())
            self.assertEqual('idle',node.logic.get_status())
            node.cmd_callback(SimpleNamespace(data=json.dumps(command)));node.timer_callback()
            self.assertEqual(before,node.logic.get_pose())
            new=str(uuid.uuid4());guard=RuntimeLease(new,clock=lambda:now[0])
            guard.accept(dict(fleetId='default',runtimeId=new,epoch=8,granted=True,retired=False,ttlMillis=12000),11,'default')
            other=FakeRobotNode(node_name='new_owner',lease=guard,use_global_arguments=False,
                                parameter_overrides=[Parameter('simulation_runtime_id',value=new)])
            self.assertNotEqual(node.pose_pub.topic_name,other.pose_pub.topic_name)
            other.cmd_callback(SimpleNamespace(data=json.dumps(command)))
            self.assertEqual((0.0,0.0),other.logic.get_pose())
            self.assertEqual(100.0,other.logic.get_battery())
        finally:
            if other:other.destroy_node()
            node.destroy_node();rclpy.shutdown()
