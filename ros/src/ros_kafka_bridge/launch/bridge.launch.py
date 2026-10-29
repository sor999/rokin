"""브릿지 노드만 단독으로 기동하는 launch 파일.

실제 운영에서는 full_demo.launch.py를 사용해 fake_robot과 함께 기동한다.
"""
import os
import json

from ament_index_python.packages import get_package_share_directory
from launch import LaunchDescription
from launch_ros.actions import Node


def generate_launch_description():
    pkg_dir = get_package_share_directory('ros_kafka_bridge')
    config = os.path.join(pkg_dir, 'config', 'bridge_config.yaml')

    return LaunchDescription([
        Node(
            package='ros_kafka_bridge',
            executable='ros_kafka_bridge_node',
            name='ros_kafka_bridge_node',
            # Humble의 dict→YAML 재직렬화에서 문자열 인용이 사라지므로 ROS CLI로 전달한다.
            ros_arguments=['--params-file', config, *[argument for key, env in (
                ('fleet_id', 'FLEET_ID'),
                ('ownership_generation', 'FLEET_OWNERSHIP_GENERATION'),
            ) if env in os.environ
                for argument in ('-p', f'ros_kafka_bridge_node:{key}:={json.dumps(os.environ[env])}')]],
            output='screen',
        )
    ])
