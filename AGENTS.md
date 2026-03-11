# 로봇 텔레메트리 플랫폼 AI 에이전트 라우팅 가이드 (Agent Router)

이 파일은 AI 에이전트가 이 모노레포(Monorepo) 프로젝트에서 작업을 수행할 때 참고해야 할 **메인 진입점(Entry point)**입니다.
에이전트는 사용자의 요청 사항에 따라 아래 명시된 도메인별 설계 가이드(Skills)를 반드시 로드하고 규칙을 준수하여 코드를 작성해야 합니다.

## 📌 도메인별 아키텍처 가이드 라우팅

사용자가 특정 스택이나 파트와 관련된 작업을 지시하면, 에이전트는 **작업 시작 전 아래 경로의 스킬 문서를 우선적으로 읽고(view_file)** 그에 맞춰 코드를 구현하세요.

### 1. 🤖 ROS2 및 로봇 제어 노드 (Robot & ROS2)
- **대상 작업:** `ros/`, `ament_python`, 로봇 이동 제어, 센서 데이터 Publisher/Subscriber 관련 작업
- **참고 문서:** `.agents/skills/ros2-design/SKILL.md`

### 2. 🍃 Spring Boot 백엔드 (API)
- **대상 작업:** `services/api-spring/`, HTTP API(REST), WebSocket 통신, 클라이언트 상태 조회
- **참고 문서:** `.agents/skills/spring-design/SKILL.md`

### 3. 🐹 Go 기반 고성능 Worker
- **대상 작업:** `services/worker-go/`, Kafka Consumer, 대규모 병렬 데이터 적재(Batch Insert), sqlx 연동
- **참고 문서:** `.agents/skills/go-design/SKILL.md`

### 4. 🖥️ Next.js 웹 대시보드 (Frontend)
- **대상 작업:** `services/dashboard-next/`, React 컴포넌트, App Router, Tailwind CSS, 상태 관리, 지도 마커 및 궤적 렌더링
- **참고 문서:** `.agents/skills/nextjs-design/SKILL.md`

### 5. 🔗 Git 및 협업 컨벤션
- **대상 작업:** 브랜치 생성, 커밋 메시지 작성, PR 생성, 코드 리뷰 관련 작업
- **참고 문서:** `.agents/skills/git-conventions/SKILL.md`

### 6. 💬 코드 주석 컨벤션
- **대상 작업:** 모든 소스 파일의 인라인 주석, 섹션 구분 주석 작성
- **참고 문서:** `.agents/skills/comment-conventions/SKILL.md`

---

## 🛠️ 공통 작업 지침 (Global Directives)
1. **의존성 (Dependency) 분리:** 각 계층(ROS, Backend, Frontend)은 서로 독립적으로 빌드/실행 가능해야 합니다.
2. **비동기 통신 (Async):** 카프카 및 웹소켓 통신 시 멱등성(Idempotency)을 항상 고려하여 중복 처리 방지 로직을 작성하세요.
3. **가이드 우선 존중:** 작업 중 확신이 서지 않거나 폴더 구조를 결정해야 할 때, 에이전트가 임의로 판단하지 말고 위 라우팅 경로에 있는 `SKILL.md`의 구조를 따릅니다.

---

## 📖 도메인 용어 사전 (Glossary)
협업과 코드 작성 시 혼동을 방지하기 위해 다음 용어들을 통일하여 사용합니다.

*   **Fleet**: 관제 대상이 되는 전체 로봇들의 군집(Group). `/fleet/{robot_id}` 등의 형태로 라우팅에 주로 쓰입니다.
*   **Telemetry (텔레메트리)**: 로봇이 주기적으로(단방향으로) 뿜어내는 상태 관측 데이터. (배터리, 현재 좌표, 기기 상태 등)
*   **Command (명령)**: 사용자가 로봇에게 내리는 양방향 제어 지시. (특정 좌표로 이동, 정지 등)
*   **Ack (Acknowledge)**: Command를 수신한 로봇이 "명령을 받았다, 수행 중이다, 실패했다" 등을 시스템에 알리는 응답 데이터.
*   **Idempotency (멱등성)**: 동일한 메시지나 커맨드가 네트워크 장애 등으로 2번 이상 중복 전송되더라도, 시스템 상태(DB 저장, 로봇 이동 등)는 1번만 적용된 것과 같게 유지하는 성질.
