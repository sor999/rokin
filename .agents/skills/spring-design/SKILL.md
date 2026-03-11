---
name: spring-design
description: Spring Boot 기반 API Service 프로젝트 구조 및 아키텍처 설계 가이드
---

# Spring Boot 프로젝트 구조 및 설계 가이드

이 프로젝트는 엔터프라이즈 환경에서의 안정적인 API 제공과 WebSocket/SSE 통신을 목적으로 `api-service` 계층에 **Spring Boot** 생태계를 채택합니다. 이 스킬은 Spring 애플리케이션의 모범 사례와 아키텍처 규칙을 정의합니다.

## 1. 프로젝트 표준 디렉토리 구조 (Layered Architecture)
Controller, Service, Repository 계층으로 역할을 분리하는 가장 범용적인 패키지 구조를 권장합니다.
(언어는 Java 또는 Kotlin 모두 적용 가능합니다.)

```text
robot-platform/
└── services/
    └── api-spring/
        ├── src/
    │   ├── main/
    │   │   ├── java/com/robot/fleet/      # 핵심 패키지
    │   │   │   ├── config/                # Spring 시큐리티, CORS, WebSocket, Kafka Producer 설정
    │   │   │   ├── controller/            # REST API 및 WebSocket 엔드포인트
    │   │   │   ├── service/               # 비즈니스 로직 및 트랜잭션 처리
    │   │   │   ├── repository/            # DB 접근 계층 (Spring Data JPA, Querydsl 등)
    │   │   │   ├── domain/                # 엔티티 (JPA Entity) 클래스
    │   │   │   ├── dto/                   # 요청/응답 형식의 Data Transfer Object
    │   │   │   └── exception/             # 글로벌 에러 핸들링 (ControllerAdvice)
    │   │   └── resources/
    │   │       ├── application.yml        # 애플리케이션 설정 (DB, Kafka 연결 정보)
    │   │       └── db/migration/          # DB 마이그레이션 스크립트 (Flyway)
    │   └── test/                          # JUnit/Mockito 기반 단위 및 통합 테스트
    ├── build.gradle (또는 pom.xml)
    └── Dockerfile
```

## 2. 주요 설계 및 구현 원칙

1. **관심사의 명확한 분리 (Separation of Concerns)**:
   - **Controller**: HTTP/WS 요청을 받고 검증(Validation)한 뒤, `Service` 계층을 호출합니다. 비즈니스 로직을 직접 소유하지 않고, `DTO` 변환만 담당합니다.
   - **Service**: `@Transactional`로 묶인 주요 비즈니스 로직이 수행됩니다. 무거운 데이터 생성이나 상태 변경은 Kafka Topic으로 `Event/Command`를 Produce 하는 역할에 집중합니다. (실제 적재는 Go Worker가 담당)
   - **Repository & Domain**: Entity 객체는 DB 테이블과 매핑되며, 순수한 도메인 논리만 포함합니다.

2. **Entity와 DTO의 분리**:
   - 클라이언트에게 반환하는 API 스펙이나 요청 본문에는 절대로 `Entity(@Entity)` 클래스를 직접 노출하지 않습니다. 반드시 `DTO(Data Transfer Object)` 레코드/클래스로 매핑하여 사용하세요.

3. **의존성 주입 (Dependency Injection)**:
   - 모든 의존성은 생성자 주입(Constructor Injection) 방식을 권장합니다. (`@Autowired` 필드 주입 지양)
   - Lombok 활용 시 `@RequiredArgsConstructor`를 사용하여 깔끔하게 구성하세요.

4. **실시간 통신 아키텍처 (SSE & WebSocket)**:
   - 단방향 관측 지표(로봇 상태 업데이트 등)는 Spring WebFlux(`SseEmitter` 혹은 `Flux`)를 사용하여 연결 리소스를 최적화합니다.
   - 양방향 명령(Command 이동 지시 및 Ack 반환)은 `Spring WebSocket` 및 (필요 시) `STOMP` 프로토콜을 통하여 구현합니다.

5. **DB 스키마 주도권 (Flyway)**:
   - MSA 환경에서 분리된 Go Worker Service와 함께 쓰이는 데이터베이스의 **스키마 모델링 권한은 본 `api-service` 애플리케이션(Flyway)이 가집니다**.
   - `src/main/resources/db/migration/` 내에 버전별 `.sql` 파일을 통해 관리합니다.

## 3. 테스트 코드 작성 및 검증 (JUnit 5)

테스트 자동화를 중시하여, 코드를 작성할 때 테스트 규칙을 엄격하게 지킵니다.
테스트 계층화, BDD(Behavior-Driven Development), Mocking(Mockito), Assertions(AssertJ) 및 **Test Fixture Factory**에 대한 상세한 가이드라인은 다음 별도 문서를 참조하십시오:

- [테스트 컨벤션 및 픽스처 상세 가이드 보기 (`test-conventions.md`)](test-conventions.md)
## 4. 코드 주석 (Code Comments)
- Java/Kotlin의 표준 `Javadoc/KDoc` 관례를 따릅니다.
- 상세한 주석 작성 규칙(섹션 구분, 일관된 스타일)은 [공통 주석 컨벤션](../comment-conventions/SKILL.md)을 준수하세요.
