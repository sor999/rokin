---
name: nextjs-design
description: Next.js (App Router 기반) 프로젝트 구조, 컴포넌트 설계 및 프론트엔드 아키텍처 가이드
---

# Next.js 프로젝트 구조 및 설계 가이드

이 스킬은 React와 Next.js App Router (버전 13+) 생태계에 맞는 프로젝트 구조화 및 코드 작성 가이드를 제공합니다.
Next.js 프로젝트의 패키지 매니저는 반드시 **`pnpm`**을 사용합니다. (`npm`, `yarn` 사용 금지)

## 1. 프로젝트 표준 구조
`src` 디렉토리와 `pnpm`을 사용하는 것을 권장하며, 역할별로 명확하게 폴더를 분리합니다.

```text
robot-platform/
└── services/
    └── dashboard-next/
        ├── src/
│   ├── app/                 # App Router 기반의 페이지 및 라우팅 (page.tsx, layout.tsx)
│   │   ├── (dashboard)/     # Route 그룹핑을 통한 인증/레이아웃 적용
│   │   │   ├── fleet/       # /fleet 경로
│   │   │   └── robots/      # /robots 경로
│   │   └── api/             # Next.js Route Handlers (BFF 계층 필요 시)
│   ├── components/          # 재사용 가능한 UI 컴포넌트
│   │   ├── layouts/         # 공통 레이아웃 (Sidebar, Header 등)
│   │   ├── ui/              # 버튼, 인풋 등 기본 요소 (shadcn/ui 등)
│   │   └── features/        # 로봇 맵, 커맨드 폼 등 비즈니스 종속적 컴포넌트
│   ├── lib/                 # 서드파티 라이브러리 설정, 유틸리티, SSE/WS 클라이언트 (utils.ts)
│   ├── hooks/               # 커스텀 React 훅 (useWebSocket, useRobotTelemetry 등)
│   ├── services/            # 외부 API (FastAPI) 통신 로직 및 Fetchers
│   ├── store/               # 전역 상태 관리 (Zustand 등)
│   └── types/               # 전역 TypeScript 인터페이스 (Robot, Telemetry 등)
├── public/                  # 정적 에셋 (이미지, 폰트)
└── tailwind.config.ts       # 스타일링 프레임워크 설정 
```

## 2. 개발 및 설계 원칙

1. **Server Component와 Client Component 분리**:
   - 기본적으로 App 디렉토리 내 모든 컴포넌트는 Server Components입니다. SEO와 초기 렌더링 성능 최적화를 위해 가급적 서버 컴포넌트로 유지하세요.
   - 상태 관리(useState, useEffect), 이벤트 핸들러(onClick), 브라우저 전용 API(SSE, WebSocket) 등 상호작용이 필요한 경우만 파일 최상단에 `"use client"`를 선언하고 분리하세요.

2. **재사용 가능한 훅 (Custom Hooks)**:
   - 복잡한 뷰 컴포넌트는 순수 UI 레더링만 담당하도록 `features` 컴포넌트로 작성하고, 
   - 데이터 페칭(FastAPI polling/SSE)이나 WebSocket 상태 관리 로직은 `useWebSocket`, `useRobotRealtime` 과 같이 Custom Hook으로 추출하여(`hooks/`) 사용성을 높이세요.

3. **엄격한 TypeScript 사용**:
   - `any` 타입 사용을 지양하고, 서버에서 내려오는 JSON 규격(Envelope, Robot Status 등)을 `src/types/`에 인터페이스로 정확히 정의하여 프론트엔드 전역에서 안전하게 사용하세요.

4. **단방향 데이터 흐름 및 상태 관리**:
   - 대규모 뷰 관리(예: 다중 로봇의 최신 상태 목록)를 위해 전역 상태가 필요한 경우 `Context API`나 경량 라이브러리인 `Zustand`를 사용하세요.

5. **UI 시스템 및 스타일링 (IBM Style & shadcn/ui)**:
   - 인터페이스 디자인은 **IBM Carbon Design System** 특유의 절제되고 프로페셔널한 느낌(다크 테마, 정돈된 그리드, 블루/그레이 톤, 그리고 명확한 타이포그래피)을 지향해야 합니다.
   - 기본 컴포넌트는 반드시 **`shadcn/ui`**를 설치하여 사용하며, 테마 커스터마이징 시 IBM 스타일의 미니멀리즘과 가독성을 반영하세요.
   - CSS-in-JS 보다 Tailwind CSS를 권장하며, 조건부 스타일링 등은 `tailwind-merge`, `clsx` 와 같은 유틸리티(`lib/utils.ts`)를 조합하여 깨끗하게 유지하세요.

6. **환경 변수 (Environment Variables)**:
   - 클라이언트(브라우저) 환경에 노출되어야 하는 환경 변수(예: 외부 API URL, WebSocket 주소)는 반드시 `NEXT_PUBLIC_` 접두사를 붙여 정의합니다 (예: `NEXT_PUBLIC_API_URL`).
   - 환경 변수 타입 검증을 위해 `env.mjs` (또는 Zod)를 도입하는 것을 적극 고려하세요.
