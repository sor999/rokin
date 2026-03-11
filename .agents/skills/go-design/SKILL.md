---
name: go-design
description: Go(Golang) 기반 고성능 Worker Service 설계 및 아키텍처 가이드
---

# Go 기반 Worker Service 구조 및 설계 가이드

이 프로젝트는 초당 수만 건의 대규모 트래픽(Kafka Telemetry)을 안전하고 빠르게 DB에 적재하기 위해 Worker 계층을 Go 언어로 개발합니다. 이 스킬은 Go 애플리케이션의 모범 사례와 아키텍처 규칙을 정의합니다.

## 1. 프로젝트 표준 디렉토리 구조 (Standard Go Project Layout)
Go 진영의 비공식 표준 레이아웃인 `golang-standards/project-layout` 원칙을 단순화하여 사용합니다.

```text
robot-platform/
└── services/
    └── worker-go/
        ├── cmd/
    │   └── worker/
    │       └── main.go         # 애플리케이션 진입점 (Configuration 로드 및 실행)
    ├── internal/               # 외부 패키지에서 import 할 수 없는 도메인 로직
    │   ├── config/             # 환경 변수 구조체 정의 (envconfig 등 사용)
    │   ├── kafka/              # Kafka Consumer/Producer 래퍼 (confluent-kafka-go)
    │   ├── repository/         # DB 접근 계층 (sqlx 등 사용)
    │   └── handler/            # 컨슈밍된 메시지의 파싱 및 비즈니스 처리 연결
    ├── pkg/                    # (선택) 다른 프로젝트에서도 쓸 수 있는 공용 라이브러리 (Logger 등)
    ├── go.mod
    ├── go.sum
    └── Dockerfile
```

## 2. 주요 설계 및 구현 원칙

1. **에러 핸들링**:
   - Go의 전통적인 `if err != nil` 패턴을 엄격하게 지키며, 에러 발생 시 로그에 충분한 컨텍스트(작업 ID, 파티션 번호 등)를 담아 `return fmt.Errorf(...)` 형태로 상위로 전파하세요.

2. **동시성 모델 (Goroutines & Channels)**:
   - Kafka에서 가져온 메시지는 고루틴을 통해 병렬로 처리합니다.
   - 단, DB 커넥션 풀이 고갈되지 않도록 Worker Pool 패턴이나 Channel 버퍼 제한을 두어 "동시 실행 개수(Concurrency Limit)"를 제어해야 합니다.

3. **데이터베이스 접근 (순수 SQL 위주)**:
   - 수만 건의 단순 `INSERT`가 주 목적이므로, 복잡한 ORM(GORM)보다는 `sqlx`나 `pgx` 라이브러리를 사용해 **Batch Insert**를 튜닝하는 데 집중합니다.
   - DB 테이블 생성 및 마이그레이션 도구 세팅(예: `golang-migrate`)은 Worker 단독으로 가질지, API(Alembic) 쪽에 일임할지 프로젝트 합의를 따릅니다.

4. **의존성 주입 (Dependency Injection)**:
   - 구조체(Struct)와 인터페이스(Interface)를 활용하여 `internal/handler`가 `internal/repository`와 `internal/kafka`를 느슨하게 참조하게 만드세요. 코드 테스트 작성을 매우 쉽게 만들어 줍니다.

## 5. 코드 주석 (Code Comments)
- Go의 표준 `Godoc` 관례를 따릅니다. (선언 바로 위 `//` 주석)
- 상세한 주석 작성 규칙(섹션 구분 등)은 [공통 주석 컨벤션](../../comment-conventions/SKILL.md)을 준수하세요.
