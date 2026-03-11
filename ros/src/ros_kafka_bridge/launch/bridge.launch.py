"""브릿지 노드만 단독으로 기동하는 launch 파일.

실제 운영에서는 full_demo.launch.py를 사용해 fake_robot과 함께 기동한다.
"""
import os

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
            parameters=[config],
            output='screen',
        )
    ])
