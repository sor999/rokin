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

## 2. 언어별 문서화 주석 (Doc Comments)

함수, 클래스, 인터페이스 등의 선언부 위에는 해당 언어의 관례에 맞는 **문서화 주석**을 작성합니다. 모든 언어에서 **간결함**을 유지하며, 불필요한 장식용 기호는 배제합니다.

### Python (Docstring)
- `""" ... """` 형식을 사용합니다.
```python
def make_envelope(data: dict) -> dict:
    """공통 Telemetry Envelope를 생성한다."""
    ...
```

### Go (Godoc)
- 선언 바로 위에 `//`로 시작하는 주석을 작성합니다. 주석은 해당 식별자의 이름으로 시작하는 것이 관례입니다.
```go
// NewProducer 는 카프카 프로듀서 인스턴스를 생성합니다.
func NewProducer(broker string) *Producer {
    ...
}
```

### Java / Kotlin (Javadoc/KDoc)
- `/** ... */` 형식을 사용하며, 표준 태그(@param, @return 등)는 꼭 필요한 경우에만 최소한으로 사용합니다.
```java
/**
 * 로봇의 현재 상태를 DB에 저장합니다.
 */
public void saveStatus(RobotStatus status) {
    ...
}
```

### TypeScript / JavaScript (JSDoc)
- `/** ... */` 형식을 사용합니다.
```typescript
/**
 * 지도 위에 로봇 마커를 렌더링합니다.
 * @param robotId 로봇 고유 식별자
 */
const renderMarker = (robotId: string) => {
    ...
}
```

## 3. 적용 범위 및 요약

이 규칙은 프로젝트 전체에 적용됩니다. 주석 작성 시 아래 표를 참고하세요.

| 언어 | 인라인 섹션 주석 (`#1`) | 문서화 주석 (Doc Comments) |
|---|---|---|
| **Python** | `# 섹션명` | `""" Docstring """` |
| **Go** | `// 섹션명` | `// Godoc` |
| **Java/Kotlin** | `// 섹션명` | `/** Javadoc/KDoc */` |
| **TypeScript/JS** | `// 섹션명` | `/** JSDoc */` |

> [!IMPORTANT]
> 어떤 언어를 사용하더라도 코드 블록 구분을 위해 `// ---` 나 `// ===` 같은 구분선을 사용하는 것은 금지됩니다. 공백과 간단한 텍스트 주석만으로 섹션을 구분하세요.
