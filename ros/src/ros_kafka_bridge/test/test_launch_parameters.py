import importlib.util
import os
from pathlib import Path
import unittest
from unittest.mock import patch


@unittest.skipUnless(importlib.util.find_spec('launch_ros'), 'requires ROS2 launch_ros')
class LaunchParameterTest(unittest.TestCase):
    def test_environment_overrides_remain_strings_in_both_launch_files(self):
        import rclpy
        from rclpy.node import Node

        rclpy.init(args=[])
        try:
            for filename in ('bridge.launch.py', 'full_demo.launch.py'):
                path = Path(__file__).resolve().parents[1] / 'launch' / filename
                spec = importlib.util.spec_from_file_location('bridge_launch_test', path)
                module = importlib.util.module_from_spec(spec)
                spec.loader.exec_module(module)
                for fleet, generation in (('Y', '1'), ('true', 'on'), ('123', '0001')):
                    with self.subTest(filename=filename, fleet=fleet, generation=generation), \
                            patch.dict(os.environ, FLEET_ID=fleet, FLEET_OWNERSHIP_GENERATION=generation), \
                            patch.object(module, 'Node') as launch_node:
                        module.generate_launch_description()
                        options = launch_node.call_args.kwargs
                        arguments = list(options['ros_arguments'])
                        for parameter_file in options.get('parameters', []):
                            arguments.extend(['--params-file', parameter_file])
                        node = Node(options['name'], cli_args=['--ros-args', *arguments],
                                    use_global_arguments=False)
                        try:
                            node.declare_parameter('fleet_id', 'default')
                            node.declare_parameter('ownership_generation', '1')
                            node.declare_parameter('kafka_broker', 'unconfigured')
                            self.assertEqual(fleet, node.get_parameter('fleet_id').value)
                            self.assertEqual(generation, node.get_parameter('ownership_generation').value)
                            self.assertEqual('kafka:29092', node.get_parameter('kafka_broker').value)
                        finally:
                            node.destroy_node()
        finally:
            rclpy.shutdown()
