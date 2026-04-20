---
name: git-conventions
description: 프로젝트의 Git 커밋 컨벤션(한국어), 브랜치 전략, PR 규칙을 가이드합니다.
---

# Git 및 GitHub 협업 가이드 (Git/GitHub Conventions)

이 스킬은 프로젝트의 코드 버전 관리 및 협업 규칙을 정의합니다. 코드를 커밋하거나 브랜치를 분기할 때 항상 이 규칙을 따르세요.

## 1. 브랜치 전략 (Branching Strategy)

Git Flow 전략을 기반으로 합니다. 브랜치 이름에는 기능 식별자와 이슈 번호를 포함합니다.

- **`main`**: 프로덕션/배포 브랜치 (안정화 상태)
- **`develop`**: 다음 배포를 위해 기능들이 통합되는 개발 브랜치
- **`feature/`**: 새로운 기능 개발 (`develop`에서 분기, `develop`으로 병합)
  - 포맷: `feature/#<issue-number>-<short-description>`
  - 예시: `feature/#12-add-robot-pose-topic`
- **`release/`**: 배포 준비 브랜치 (`develop`에서 분기, `main`과 `develop`으로 병합)
- **`hotfix/`**: 배포된 프로덕션 버전의 긴급 이슈 수정 (`main`에서 분기, `main`과 `develop`으로 병합)
  - 포맷: `hotfix/#<issue-number>-<short-description>`
  - 예시: `hotfix/#34-websocket-crash`

## 2. 커밋 메시지 컨벤션 (Commit Conventions)

커밋 메시지는 **반드시 한국어**로 작성하며, 제목에 **이슈 번호**를 포함해야 합니다.

### 커밋 메시지 구조
```
<타입>: <제목>
타입: 짧은 설명만 쓰고, 본문 없이 진행
```

### 타입(Type) 정의
- `feat` : 새로운 기능 추가
- `fix` : 버그 수정
- `docs` : 문서 수정
- `style` : 코드 포맷팅, 세미콜론 누락, 코드 변경이 없는 경우
- `refactor` : 코드 리팩토링
- `test` : 테스트 코드, 리팩토링 테스트 코드 추가
- `chore` : 빌드 업무 수정, 패키지 매니저 수정

### 예시
```
feat: 카프카 텔레메트리 프로듀서 모듈 추가
```

## 3. Pull Request (PR) 가이드

- PR은 반드시 `.github/PULL_REQUEST_TEMPLATE.md` 형식을 준수하여 작성해야 합니다.
- **Title 형식 (모노레포 Scope 적용)**: 프로젝트가 모노레포 구조이므로, 제목의 타입 뒤에 괄호로 **영역(Scope)**을 명시하는 것을 권장합니다 (Semantic Commit 표준 적용).
    - 영역(Scope) 예시: `api`, `worker`, `dashboard`, `ros`, `infra`, `docs` 등
    - 템플릿: `<타입>(<영역>): <제목> (#<이슈번호>)`
    - 예시: `feat(api): 로봇 이동 명령 API 추가 (#15)`
    - 예시: `fix(dashboard): 지도 마커 렌더링 오류 수정 (#20)`
- 기능 개발 완료 후 `develop` 브랜치(또는 지정된 대상 브랜치)로 PR을 생성합니다.
- PR 본문에서는 어떤 이슈를 해결하는지 명시합니다 (예: `Resolves: #15`).
- **Merge 전략 (Merge Strategy)**: 기능 브랜치를 `develop` 브랜치에 병합할 때는 기본적으로 **Squash and Merge** 방식을 사용하여 커밋 히스토리를 깔끔하게 (작업 단위 1개 커밋으로) 유지합니다.
