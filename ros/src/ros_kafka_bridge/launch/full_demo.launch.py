"""fake_robot 노드 3대 + ros_kafka_bridge를 함께 기동하는 통합 launch 파일.

Docker 컨테이너에서 이 파일을 사용해 단일 프로세스 그룹으로 실행하면
DDS 로컬 통신이 보장된다.
"""
import os

from ament_index_python.packages import get_package_share_directory
from launch import LaunchDescription
from launch.actions import IncludeLaunchDescription
from launch.launch_description_sources import PythonLaunchDescriptionSource
from launch_ros.actions import Node


def generate_launch_description():
    fake_robot_pkg = get_package_share_directory('fake_robot')
    bridge_pkg = get_package_share_directory('ros_kafka_bridge')

    demo_launch = os.path.join(fake_robot_pkg, 'launch', 'demo.launch.py')
    bridge_config = os.path.join(bridge_pkg, 'config', 'bridge_config.yaml')

    return LaunchDescription([
        # fake_robot 3대 기동
        IncludeLaunchDescription(
            PythonLaunchDescriptionSource(demo_launch)
        ),
        # ROS ↔ Kafka 브릿지 노드
        Node(
            package='ros_kafka_bridge',
            executable='ros_kafka_bridge_node',
            name='ros_kafka_bridge_node',
            parameters=[bridge_config],
            output='screen',
        ),
    ])
