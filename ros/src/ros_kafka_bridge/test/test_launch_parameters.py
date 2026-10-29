import importlib.util
import os
from pathlib import Path
import unittest
from unittest.mock import patch


@unittest.skipUnless(importlib.util.find_spec('launch_ros'), 'requires ROS2 launch_ros')
class LaunchParameterTest(unittest.TestCase):
    def test_environment_overrides_remain_strings_in_both_launch_files(self):
        from launch import LaunchContext
        from launch_ros.utilities import evaluate_parameters, normalize_parameters

        for filename in ('bridge.launch.py', 'full_demo.launch.py'):
            path = Path(__file__).resolve().parents[1] / 'launch' / filename
            spec = importlib.util.spec_from_file_location('bridge_launch_test', path)
            module = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(module)
            for fleet, generation in (('Y', '1'), ('true', 'on'), ('123', '0001')):
                with self.subTest(filename=filename, fleet=fleet, generation=generation), \
                        patch.dict(os.environ, FLEET_ID=fleet, FLEET_OWNERSHIP_GENERATION=generation), \
                        patch.object(module, 'Node') as node:
                    module.generate_launch_description()
                    overrides = node.call_args.kwargs['parameters'][1]
                    evaluated = evaluate_parameters(LaunchContext(), normalize_parameters([overrides]))[0]
                    self.assertEqual(fleet, evaluated['fleet_id'])
                    self.assertIsInstance(evaluated['fleet_id'], str)
                    self.assertEqual(generation, evaluated['ownership_generation'])
                    self.assertIsInstance(evaluated['ownership_generation'], str)
