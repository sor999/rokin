---
name: ros2-design
description: ROS2 (ament_python 기반) 패키지 구조 및 노드 설계 가이드
---

# ROS2 패키지 구조 및 설계 가이드

이 스킬은 커스텀 ROS2 패키지(특히 `ament_python`) 작성 시 참조할 산업 표준 및 아키텍처 규칙을 정의합니다.

## 1. 패키지 표준 구조 (Python 기반)
로봇 노드를 관리할 때 일관성을 유지하기 위해 아래 구조를 권장합니다.
```text
ros2_ws/src/my_package/
├── my_package/          # Python 모듈 폴더 (노드 및 내부 로직 클래스 구현부)
│   ├── __init__.py
│   ├── main_node.py       # ROS2 rclpy.Node 상속 클래스
│   └── logic.py           # ROS에 종속성이 없는 순수 비즈니스 로직
├── config/              # YAML 파라미터 파일
│   └── default_params.yaml
├── launch/              # 패키지 런치 파일
│   └── my_project.launch.py
├── resource/            # 빈 파일 (ament_python 인식용)
│   └── my_package
├── package.xml          # ROS2 패키지 의존성 및 메타데이터
├── setup.py             # Entry points 설정
└── setup.cfg            # 개발/설치 환경 설정
```

## 2. ROS2 노드 설계 원칙

1. **상태와 통신의 분리**:
   - `Node` 클래스는 통신(Publisher, Subscriber, Timer, Parameter) 처리만 담당합니다.
   - 실제 비즈니스 로직, 상태 변환 스크립트는 `Node` 외부에 순수 파이썬 클래스/함수로 분리하여 테스트하기 쉽게 구성하세요.

2. **파라미터화 (Parameterization)**:
   - 로봇 식별자(`robot_id`), 타이머 주기(`publish_rate_hz`), 통신 포트 등 하드코딩될 수 있는 변수는 전부 ROS 파라미터로 처리합니다.
   - 노드 초기화 `__init__`에서 `self.declare_parameter`를 통해 기본값을 명시하세요.

3. **Graceful Shutdown**:
   - 노드가 종료될 때 안전하게 상태를 정리하고 로그를 남기도록 `try-except KeyboardInterrupt` 혹은 노드 소멸자 처리를 제대로 수행하세요.

4. **토픽 네이밍 표준**:
   - 도메인 단위로 명확하게 계층을 나눕니다. 예: `/fleet/{robot_id}/pose`, `/fleet/{robot_id}/battery`
   - Global namespace(`/`) 보다는 상대경로를 이용해 namespace 기능으로 그룹화할 수 있게 작성하는 것이 유연합니다 (다만 이 프로젝트에서는 `robot_id`를 명시적으로 URL 스타일로 사용하기로 했으므로 이에 따릅니다).

## 3. Entry point 설정 (`setup.py`)
`console_scripts`에 노드 실행 엔트리를 반드시 다음과 같이 구성하세요.
```python
entry_points={
    'console_scripts': [
        'fake_robot_node = fake_robot.fake_robot_node:main',
    ],
},
```
## 4. 코드 주석 (Code Comments)
- 기본적으로 Python `Docstring` 관례를 따릅니다.
- 상세한 주석 작성 규칙(섹션 구분, 언어 등)은 [공통 주석 컨벤션](../../comment-conventions/SKILL.md)을 준수하세요.
