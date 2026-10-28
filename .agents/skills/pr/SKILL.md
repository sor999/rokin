---
name: pr
description: 프로젝트 변경사항을 분석하여 PR 템플릿과 scope가 있는 한국어 제목으로 PR을 작성하거나 갱신한다.
---

# 사전 확인과 브랜치

- 저장소 루트의 `AGENTS.md`와 [Git 컨벤션](../git-conventions/SKILL.md)을 확인한다.
- `git status --short`, 현재 브랜치, 대상 브랜치와의 diff, 커밋 이력을 확인한다. 지정이 없으면 대상은 `main`이다.
- 새 브랜치는 Git 컨벤션의 `feature/<issue-number>-<verb>-<feature>` 또는 `hotfix/<issue-number>-<verb>-<feature>` 형식을 따른다.
- 이슈 번호는 확인된 값만 사용한다. 이슈 작성은 [issue 스킬](../issue/SKILL.md)을 참고하고, 게시 요청 없이 이슈를 자동 생성하지 않는다.
- 기존 작업 브랜치를 임의로 바꾸거나 사용자 변경을 되돌리지 않는다.
- [검증 컨벤션](../../../docs/conventions/verification.md)에 따른 결과와 미실행 사유를 확인한다.

# PR 작성

- [PR 템플릿](../../../.github/PULL_REQUEST_TEMPLATE.md)의 구조를 유지한다.
- 제목은 `<타입>(<영역>): <한국어 요약>` 형식이다. 예: `feat(api): 로봇 정지 명령 처리 추가`.
- 타입은 `feat`, `fix`, `refactor`, `test`, `docs`, `style`, `chore` 중 선택한다. scope는 `api`, `worker`, `dashboard`, `ros`, `infra`, `docs` 등 변경 영역을 나타낸다.
- 제목에 이슈 번호를 넣지 않는다. [제목 검증 워크플로](../../../.github/workflows/pr-title.yml)와 일치시킨다.
- `✨ 변경 내용`: 해결한 문제, 변경 후 동작과 필요한 배경을 작성한다.
- `📌 관련 이슈`: 실제 관련 이슈만 연결한다. 병합으로 종료할 이슈에만 `closes #번호`를 사용한다.
- `📎 참고 사항`: 실행한 검증, 미실행 사유, 리뷰에 필요한 제약이나 후속 작업을 작성한다.
- 관련 이슈나 참고 사항이 없으면 불필요한 값을 채우지 않는다.
- 초안 요청은 제목과 본문 작성까지 수행한다. 생성/갱신 요청은 연결된 GitHub 도구 또는 `gh`로 수행하고 URL을 보고한다.
- `gh`로 여러 줄 본문을 전달할 때는 파일에 작성한 뒤 `--body-file`을 사용한다.
- 기본 병합 전략은 Squash and Merge다. PR 작성만 요청받았다면 병합하지 않는다.

# 어투

- 간결한 개조식 및 명사형 종결 사용
- 목록은 하이픈 또는 체크박스로 통일하고 중단점(·) 혼용 금지
