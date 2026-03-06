FROM ros:humble-ros-base

# 환경 변수 설정
ENV DEBIAN_FRONTEND=noninteractive
ENV PYTHONUNBUFFERED=1

# 시스템 업데이트 및 필수 빌드 툴 설치
RUN apt-get update && apt-get install -y \
    python3-colcon-common-extensions \
    python3-pip \
    libpq-dev \
    git \
 && rm -rf /var/lib/apt/lists/*

# 파이썬 의존성 설치 (추후 필요한 라이브러리 추가)
# RUN pip3 install <dependencies>

# 작업 디렉토리 생성
WORKDIR /ros_ws

# 소스 복사
COPY src/ /ros_ws/src/

# ROS2 패키지 빌드
RUN /bin/bash -c "source /opt/ros/humble/setup.bash && colcon build --symlink-install"

# 엔트리 포인트 스크립트 작성
RUN echo "#!/bin/bash" > /ros_entrypoint.sh && \
    echo "set -e" >> /ros_entrypoint.sh && \
    echo "source /opt/ros/humble/setup.bash" >> /ros_entrypoint.sh && \
    echo "if [ -f /ros_ws/install/setup.bash ]; then source /ros_ws/install/setup.bash; fi" >> /ros_entrypoint.sh && \
    echo "exec \"\$@\"" >> /ros_entrypoint.sh && \
    chmod +x /ros_entrypoint.sh

ENTRYPOINT ["/ros_entrypoint.sh"]
CMD ["bash"]
