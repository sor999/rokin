# ADR 0001: 서비스 경계와 DB 스키마 소유권

- 상태: 승인
- 날짜: 2026-08-31

## 맥락

이 저장소는 ROS2, Go, Spring Boot, Next.js를 독립 실행 단위로 유지합니다. Go Worker와
Spring API가 같은 PostgreSQL 테이블을 사용하며, Worker에는 단독 개발 실행을 위한
`CREATE TABLE IF NOT EXISTS` 호환 코드가 남아 있습니다.

## 결정

- 서비스 간 연결은 Kafka, HTTP, SSE, WebSocket/STOMP, PostgreSQL 스키마 계약으로 제한합니다.
- PostgreSQL 스키마의 정식 소유자는 Spring API의 Flyway 마이그레이션입니다.
- Worker의 `internal/repository/migrate.go`는 임시 개발 호환 폴백입니다.
- 새 컬럼, 인덱스, 제약은 새 Flyway 마이그레이션에 먼저 추가합니다.
- 폴백이 남아 있는 동안 Worker DDL과 SQL 호환성을 같은 변경에서 검토합니다.
- 장기적으로 로컬 개발 절차가 Flyway 적용을 보장하면 Worker DDL을 제거합니다.

## 결과

서비스별 독립 빌드는 유지하지만 스키마 정의가 이중화된 현재 위험을 명시적으로 관리합니다.
Worker DDL과 토픽 초기화의 차이는 경계 변경 시 수동으로 함께 검토합니다.

## 검증

- API: `cd services/api && ./gradlew test`
- Worker: `cd services/worker && go test ./... && go vet ./...`
- 경계 변경: Flyway, JPA Entity, Worker SQL/DDL diff를 함께 리뷰
