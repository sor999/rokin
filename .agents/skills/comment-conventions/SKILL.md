---
name: comment-conventions
description: 프로젝트 전체에서 사용하는 코드 주석(Comment) 작성 컨벤션
---

# 코드 주석 컨벤션 (Comment Conventions)

이 스킬은 이 프로젝트의 모든 소스 코드에서 주석을 작성할 때 따라야 할 규칙을 정의합니다.

## 1. 인라인 섹션 주석

코드 블록을 구분하는 섹션 주석은 **심플하게** 작성합니다.

### 규칙

- `#` 한 개 + 공백 + 한국어 설명
- 구분선(`──`, `---`, `===` 등) **사용 금지**
- 불필요한 패딩 문자 **사용 금지**

### 올바른 예시 ✅

```python
# 파라미터 선언
self.declare_parameter('robot_id', 'robot_1')

# Kafka Producer (ROS → Kafka)
self._producer = KafkaTelemetryProducer(broker)

# ROS 구독 (Telemetry → Kafka)
for robot_id in self._robot_ids:
    ...

# 생명주기
def destroy_node(self) -> None:
    ...
```

### 잘못된 예시 ❌

```python
# ── 파라미터 선언 ──────────────────────────────────────────────────────
# ── Kafka Producer (ROS → Kafka) ───────────────────────────────────────
# === 생명주기 ===
```

## 2. 함수/클래스 Docstring

공개 메서드와 클래스에는 **한 줄 또는 멀티라인 docstring**을 사용합니다.
섹션 주석과 달리 docstring은 삭제하지 않습니다.

```python
def _make_envelope(self, robot_id: str, msg_type: str, data: dict) -> dict:
    """공통 Telemetry Envelope를 생성한다."""
    ...

class RosKafkaBridgeNode(Node):
    """ROS2 ↔ Kafka 데이터 브릿지 노드.

    - ROS → Kafka (Telemetry): pose / battery / status / ack 토픽을 Kafka로 Produce
    - Kafka → ROS (Command): cmd.robot 토픽을 Consume해 각 로봇의 /fleet/{robot_id}/cmd 로 Relay
    """
```

## 3. 적용 범위

이 규칙은 언어에 관계없이 프로젝트 전체에 적용됩니다.

| 언어 | 섹션 주석 형식 |
|---|---|
| Python | `# 섹션명` |
| Go | `// 섹션명` |
| Java/Kotlin | `// 섹션명` |
| TypeScript/JS | `// 섹션명` |
