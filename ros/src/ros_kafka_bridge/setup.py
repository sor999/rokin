from setuptools import find_packages, setup
import os
from glob import glob

package_name = 'ros_kafka_bridge'

setup(
    name=package_name,
    version='0.0.0',
    packages=find_packages(exclude=['test']),
    data_files=[
        ('share/ament_index/resource_index/packages',
            ['resource/' + package_name]),
        ('share/' + package_name, ['package.xml']),
        (os.path.join('share', package_name, 'launch'), glob(os.path.join('launch', '*launch.[pxy][yma]*'))),
        (os.path.join('share', package_name, 'config'), glob(os.path.join('config', '*.yaml'))),
    ],
    install_requires=['setuptools', 'confluent-kafka'],
    zip_safe=True,
    maintainer='sor999',
    maintainer_email='sorsor999@naver.com',
    description='ROS2-Kafka 브릿지: 텔레메트리 Produce 및 Command Consume/Relay',
    license='MIT',
    tests_require=['pytest'],
    entry_points={
        'console_scripts': [
            'ros_kafka_bridge_node = ros_kafka_bridge.bridge_node:main',
        ],
    },
)
