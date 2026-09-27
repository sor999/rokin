---
description: 저장소 템플릿에 맞춘 GitHub PR 생성
argument-hint: [PR 요구사항]
allowed-tools: Read, Grep, Glob, Bash(git status:*), Bash(git diff:*), Bash(git log:*), Bash(git branch:*), Bash(git remote:*), Bash(git push:*), Bash(gh pr:*)
disable-model-invocation: true
---

# GitHub PR 생성

요구사항: $ARGUMENTS

## 사전 확인

1. `.github/PULL_REQUEST_TEMPLATE.md`를 직접 읽고 현재 형식 적용
2. `.agents/skills/git-conventions/SKILL.md`를 읽고 브랜치와 PR 규칙 적용
3. 현재 브랜치, 대상 브랜치, 변경 파일, 커밋, 테스트 결과 확인
4. 동일 브랜치의 기존 PR 확인
5. 변경 사항이나 커밋이 없으면 PR 생성 중단

## 작성 원칙

- 한국어 개조식 사용
- `~합니다`, `~한다` 형태의 서술문 지양
- 가운데점 문자 사용 금지
- 병렬 항목은 쉼표, `및`, `와/과`로 구분
- 실제 diff와 커밋에 있는 내용만 작성
- 확인하지 않은 테스트 결과 작성 금지
- Squash and Merge 기준 적용
- PR 제목에 이슈 번호 추가 금지
- 관련 이슈와 참고 사항은 실제 내용이 있을 때만 작성
- 관련 내용이 없으면 해당 섹션을 비워 두고 임의 값 추가 금지

## 제목 형식

```text
<타입>(<영역>): <짧고 구체적인 변경명>
```

- 타입: `feat`, `fix`, `refactor`, `test`, `docs`, `style`, `chore`
- 영역 예시: `api`, `worker`, `dashboard`, `ros`, `infra`, `docs`
- 이슈 번호 제외

## 본문 형식

```markdown
## ✨ 변경 내용
- 실제 변경 내용

## 📌 관련 이슈
- 실제 관련 이슈

## 📎 참고 사항
- 실제 참고 사항
```

## 생성 절차

1. 현재 브랜치와 원격 추적 상태 확인
2. 사용자 요청과 실제 diff를 기준으로 제목과 본문 작성
3. 사용자가 미리보기만 요청한 경우 생성 없이 초안 출력
4. 원격 브랜치가 없으면 현재 브랜치만 push
5. `gh pr create --base main`으로 PR 생성
6. `gh pr view`로 제목, 대상 브랜치, 본문 형식 확인
7. 최종 응답에 PR 제목과 링크만 보고
