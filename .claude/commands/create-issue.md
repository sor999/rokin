---
description: 저장소 템플릿에 맞춘 GitHub 이슈 생성
argument-hint: [이슈 요구사항]
allowed-tools: Read, Grep, Glob, Bash(git remote:*), Bash(gh issue:*)
disable-model-invocation: true
---

# GitHub 이슈 생성

요구사항: $ARGUMENTS

## 사전 확인

1. `$ARGUMENTS`가 비어 있으면 필요한 이슈 내용을 한 문장으로 질문하고 중단
2. 아래 저장소 템플릿을 직접 읽고 현재 형식 그대로 적용
   - 기능 요청: `.github/ISSUE_TEMPLATE/feature_request.md`
   - 버그 보고: `.github/ISSUE_TEMPLATE/bug_report.md`
3. `git remote -v`로 대상 저장소 확인
4. `gh issue list --state all`로 제목과 범위가 유사한 기존 이슈 검색
5. 중복 이슈가 있으면 새로 생성하지 않고 기존 이슈 링크와 중복 근거 보고

## 작성 원칙

- 한국어 개조식 사용
- `~합니다`, `~한다` 형태의 서술문 지양
- 가운데점 문자 사용 금지
- 병렬 항목은 쉼표, `및`, `와/과`로 구분
- 템플릿에 없는 완료 조건, 검증 계획, 작업 범위, PR 안내 추가 금지
- 내부 계획 문서나 분석 원문 게시 금지
- 확인되지 않은 수치나 요구사항 생성 금지
- 구현 기능에는 목적과 필요한 이유만 작성
- 실제 구현 범위는 TODO 체크박스로 작성

## 기능 요청 형식

- 제목: `feat: <짧고 구체적인 기능명>`
- 라벨: `enhancement`
- 본문:

```markdown
## ✨ 구현할 기능
- 기능과 필요한 이유

## 📝 TODO
- [ ] 구현 항목
- [ ] 구현 항목
```

## 버그 보고 형식

- 제목: `bug: <짧고 구체적인 문제명>`
- 라벨: `bug`
- 본문:

```markdown
## 🐛 버그 내용
- 발생한 문제와 수정이 필요한 이유

## 📝 TODO
- [ ] 수정 항목
- [ ] 수정 항목
```

## 생성 절차

1. 요구사항에 맞는 템플릿, 제목, 라벨 선택
2. 사용자가 미리보기만 요청한 경우 생성 없이 초안 출력
3. 그 외에는 `gh issue create`로 현재 저장소에 이슈 생성
4. `gh issue view`로 제목, 라벨, 템플릿 형식, 개조식 표현 확인
5. 최종 응답에 이슈 번호, 제목, 링크만 보고
