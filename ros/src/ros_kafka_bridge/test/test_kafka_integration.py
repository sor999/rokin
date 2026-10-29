import base64
import json
import os
import threading
import time
import unittest
import uuid

from confluent_kafka import Consumer, Producer
from confluent_kafka.admin import AdminClient, NewTopic

from ros_kafka_bridge.commands import FleetCommandRouter
from ros_kafka_bridge.consumer import KafkaCommandConsumer


@unittest.skipUnless(os.environ.get('KAFKA_TEST_BROKER'), 'KAFKA_TEST_BROKER requires an isolated broker')
class KafkaRoutingTest(unittest.TestCase):
    def test_fleet_delivery_replay_and_dlq(self):
        from fake_robot.commands import RobotCommands
        from fake_robot.logic import FakeRobotLogic

        broker = os.environ['KAFKA_TEST_BROKER']
        suffix = uuid.uuid4().hex
        topic, dlq_topic = 'test-cmd-' + suffix, 'test-dlq-' + suffix
        admin = AdminClient({'bootstrap.servers': broker})
        for future in admin.create_topics([NewTopic(topic, 3, 1), NewTopic(dlq_topic, 1, 1)]).values():
            future.result(timeout=15)
        consumers = []
        states = {}
        done = threading.Event()
        lock = threading.Lock()
        deliveries = []
        observed = []
        observed_all = threading.Event()
        attempts = []
        try:
            for fleet, robot in (('site-a', 'robot_1'), ('site-a', 'robot_2'), ('site-b', 'robot_3')):
                commands = RobotCommands(FakeRobotLogic(battery_drain_rate=0))
                states[(fleet, robot)] = commands

                def publish(command, commands=commands, fleet=fleet, robot=robot):
                    with lock:
                        attempts.append((fleet, robot, command['cmd_id']))
                        if (fleet, robot) == ('site-a', 'robot_1') and sum(item[:2] == (fleet, robot) for item in attempts) <= 3:
                            raise RuntimeError('injected temporary relay failure')
                        commands.handle(command['cmd_id'], command['command'], command['data'])
                        deliveries.append((fleet, robot, command['command']))
                        if len(deliveries) == 6:
                            done.set()

                router = FleetCommandRouter(fleet, [robot], publish,
                                            fleet_robot_ids=['robot_1', 'robot_2'] if fleet == 'site-a' else ['robot_3'])

                def dispatch(command, router=router, fleet=fleet):
                    router.dispatch(command)
                    with lock:
                        observed.append((fleet, command['cmd_id']))
                        if len(observed) == 18:
                            observed_all.set()

                consumer = KafkaCommandConsumer(broker, router.consumer_group('test-' + suffix),
                                                topic, dispatch, dlq_topic,
                                                retry_seconds=0.01)
                consumers.append(consumer)
                consumer.start()
                self.assertTrue(consumer.ready.wait(15), 'Kafka assignment timed out')
            producer = Producer({'bootstrap.servers': broker})
            move_id, stop_id = str(uuid.uuid4()), str(uuid.uuid4())
            move = {'fleet_id': 'site-a', 'robot_id': 'robot_1', 'cmd_id': move_id,
                    'command': 'move_to', 'data': {'x': 100, 'y': 0}}
            stop = dict(move, cmd_id=stop_id, command='stop', data={})
            other_local = dict(stop, robot_id='robot_2', cmd_id=str(uuid.uuid4()))
            other = dict(stop, fleet_id='site-b', robot_id='robot_3', cmd_id=str(uuid.uuid4()))
            messages = [move, move, stop, move, other_local, other]
            for command in messages:
                producer.produce(topic, key=command['robot_id'], value=json.dumps(command).encode())
            self.assertEqual(0, producer.flush(10))
            self.assertTrue(done.wait(15), 'command callbacks timed out')
            self.assertTrue(observed_all.wait(15), 'each fleet group must see every valid envelope')
            self.assertEqual(4, sum((fleet, robot) == ('site-a', 'robot_1') for fleet, robot, action in deliveries))
            self.assertEqual(1, sum((fleet, robot) == ('site-a', 'robot_2') for fleet, robot, action in deliveries))
            self.assertEqual(1, sum(fleet == 'site-b' for fleet, robot, action in deliveries))
            self.assertEqual('idle', states[('site-a', 'robot_1')].logic.get_status())
            self.assertIsNone(states[('site-a', 'robot_1')].pending_move_id)
            invalid = dict(stop, robot_id='not-owned', cmd_id=str(uuid.uuid4()))
            raw = json.dumps(invalid).encode()
            producer.produce(topic, key='not-owned', value=raw)
            self.assertEqual(0, producer.flush(10))
            dlq = Consumer({'bootstrap.servers': broker, 'group.id': 'verify-' + suffix,
                            'auto.offset.reset': 'earliest', 'enable.auto.commit': False})
            try:
                dlq.subscribe([dlq_topic])
                deadline = time.monotonic() + 15
                record = None
                while record is None and time.monotonic() < deadline:
                    candidate = dlq.poll(1)
                    if candidate is not None and not candidate.error():
                        record = candidate
                self.assertIsNotNone(record, 'DLQ record was not received')
                payload = json.loads(record.value())
                self.assertEqual(raw, base64.b64decode(payload['original_payload_base64']))
                self.assertEqual(topic, payload['source_topic'])
                self.assertIn('not owned', payload['error'])
            finally:
                dlq.close()
        finally:
            for consumer in consumers:
                consumer.stop()
