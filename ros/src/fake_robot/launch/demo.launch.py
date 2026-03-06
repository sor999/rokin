from launch import LaunchDescription
from launch_ros.actions import Node
import os
from ament_index_python.packages import get_package_share_directory

def generate_launch_description():
    pkg_dir = get_package_share_directory('fake_robot')
    config = os.path.join(pkg_dir, 'config', 'default_params.yaml')

    return LaunchDescription([
        Node(
            package='fake_robot',
            executable='fake_robot_node',
            name='robot_1_node',
            parameters=[
                config,
                {'robot_id': 'robot_1', 'start_x': 0.0, 'start_y': 0.0, 'speed': 1.5, 'publish_rate_hz': 2.0}
            ]
        ),
        Node(
            package='fake_robot',
            executable='fake_robot_node',
            name='robot_2_node',
            parameters=[
                config,
                {'robot_id': 'robot_2', 'start_x': 5.0, 'start_y': 5.0, 'speed': 1.0, 'publish_rate_hz': 5.0}
            ]
        ),
        Node(
            package='fake_robot',
            executable='fake_robot_node',
            name='robot_3_node',
            parameters=[
                config,
                {'robot_id': 'robot_3', 'start_x': -5.0, 'start_y': 2.0, 'speed': 2.0, 'publish_rate_hz': 1.0}
            ]
        )
    ])
