# Robot Telemetry Platform 에이전트 가이드

이 파일은 저장소 전체에 적용되는 유일한 `AGENTS.md`이자 공통 완료 게이트입니다.
하위 디렉터리에 같은 규칙을 반복하지 않고, 기술별 작업 규칙은 `.agents/skills/`,
프로젝트 계약은 `docs/`에서 필요한 만큼 읽습니다.

## 프로젝트와 기준 문서

- 시스템 구조와 데이터 흐름: [ARCHITECTURE.md](ARCHITECTURE.md)
- 도메인 계약과 멱등성: [docs/domain/telemetry.md](docs/domain/telemetry.md)
- 검증 명령과 게이트 구분: [docs/conventions/verification.md](docs/conventions/verification.md)
- 구조적 결정: [docs/decisions/](docs/decisions/)

## 작업 영역 라우팅

| 영역 | 필수 기술 스킬 | 함께 읽을 기준 문서 |
| --- | --- | --- |
| ROS2 로봇/브릿지 | `.agents/skills/ros2/SKILL.md` | `ARCHITECTURE.md`, `docs/domain/telemetry.md`, `ros/README.md` |
| Spring API | `.agents/skills/spring/SKILL.md` | `ARCHITECTURE.md`, `docs/domain/telemetry.md` |
| Go Worker | `.agents/skills/go/SKILL.md` | `ARCHITECTURE.md`, `docs/domain/telemetry.md` |
| Next.js Dashboard | `.agents/skills/nextjs/SKILL.md` | `ARCHITECTURE.md`, `docs/domain/telemetry.md` |
| Git/PR | `.agents/skills/git-conventions/SKILL.md` | 이 파일의 안전과 변경 통제 |
| 모든 코드 주석 | `.agents/skills/comment-conventions/SKILL.md` | 해당 언어의 기술 스킬 |

대상 영역의 코드를 바꾸기 전에 해당 스킬을 읽습니다. 여러 영역을 잇는 변경이면
관련 스킬을 모두 적용하고, 구체적인 검증 명령은 `docs/conventions/verification.md`를 따릅니다.

## 공통 작업 흐름

1. `git status --short`와 관련 문서/테스트를 확인해 변경 범위를 정합니다.
2. 넓은 기능 변경은 구현 전에 사용자 흐름, 메시지 흐름, 자동/수동 검증을 짧게 적습니다.
3. 기존 계층, 도구, 패키지 매니저를 유지하고 요청 밖의 리팩터링을 섞지 않습니다.
4. 가장 가까운 단위 테스트부터 실행한 뒤 공통 완료 게이트를 실행합니다.
5. 계약, 상태 의미, 폴백, 영속성 또는 네트워크 경계가 바뀌면 ADR 필요 여부를 검토합니다.

## 완료 게이트

- `docs/conventions/verification.md`에서 변경한 서비스의 테스트와 빌드를 직접 실행합니다.
- 공통 계약이나 인프라 경계를 바꾸면 영향을 받는 모든 서비스 명령을 실행합니다.
- 문서만 바뀌면 `git diff --check`와 변경한 링크를 확인합니다.
- Docker 통합, 브라우저 시각 검증, 실제 ROS2 토픽 검증은 환경 의존 검증이므로 별도로 수행합니다.

## 안전과 변경 통제

- 비밀값을 코드, 문서, 로그, 스크린샷에 기록하지 않습니다. `.env.example`에는 예시만 둡니다.
- 데이터 삭제, 볼륨 초기화, 마이그레이션 재작성, 외부 배포는 사용자의 명시적 요청 없이 수행하지 않습니다.
- `scripts/reset-test-env.sh`는 볼륨을 삭제하므로 사용자가 요청한 통합 테스트에서만 실행합니다.
- 사용자 변경이 있는 dirty worktree에서는 관련 없는 파일을 되돌리거나 덮어쓰지 않습니다.

## 코드 리뷰 규칙

- Controller가 Repository를 직접 호출하거나 Entity를 API 응답으로 노출하는지 확인합니다.
- Worker 적재 경로가 멱등성 키와 제한된 동시성을 보존하는지 확인합니다.
- ROS Node에 순수 상태/비즈니스 로직이 새로 섞이지 않았는지 확인합니다.
- 클라이언트 컴포넌트에 서버 비밀값이나 불필요한 네트워크 로직이 들어가지 않았는지 확인합니다.
- 테스트가 행복 경로만이 아니라 중복 메시지, 잘못된 payload, 연결 실패를 다루는지 확인합니다.
