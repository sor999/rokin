# Spring Boot 통합 테스트 및 단위 테스트 컨벤션

이 문서는 `.agents/skills/spring-design/SKILL.md`에서 참조하는 상세 테스트 코드 작성 가이드라인입니다.

## 1. 프레임워크 및 코어 라이브러리
- **JUnit 5 (Jupiter)**: 기본 실행 프레임워크입니다. (JUnit 4 `org.junit.Test` 절대 사용 금지)
- **Mockito**: 가벼운 단위 테스트를 위해 외부 의존성(Service, Repository 등)을 Mocking합니다.
- **AssertJ**: `assertThat()`을 사용하여 체이닝 문법으로 가독성 높은 단언문(Assertion)을 작성합니다.

## 2. BDD(Behavior-Driven Development) 패턴
모든 테스트 코드는 명확한 시나리오를 전달하기 위해 **`given - when - then`** 블록 구조를 강제합니다.

```java
@Test
@DisplayName("로봇 ID로 텔레메트리 데이터를 정상적으로 조회한다.")
void findTelemetryByRobotId_Success() {
    // given (준비)
    String robotId = "robot-1";
    Telemetry dummyTelemetry = TelemetryFixture.createDefault(robotId);
    given(telemetryRepository.findByRobotId(robotId)).willReturn(Optional.of(dummyTelemetry));

    // when (실행)
    TelemetryResponse result = telemetryService.findByRobotId(robotId);

    // then (검증)
    assertThat(result).isNotNull();
    assertThat(result.robotId()).isEqualTo(robotId);
}
```
- **테스트 명명규칙**: `테스트대상메서드명_예상결과()` 형태를 취하며, `@DisplayName`에는 명확한 한글 문장을 적습니다.

## 3. Test Fixture Factory (픽스처 모음) 활용
복잡한 도메인 객체(Entity, DTO) 생성을 테스트 코드 안에서 수십 번 반복(`builder()...build()`)하지 마세요. 구상 및 관리가 어려워집니다.

- **방법**: `src/test/java/com/robot/fleet/fixture` 경로 등에 엔티티별 팩토리 정적 클래스를 만듭니다.
- **예시**: `TelemetryFixture.java`, `RobotFixture.java`

```java
public class RobotFixture {
    public static Robot createDefault(String robotId) {
        return Robot.builder()
            .robotId(robotId)
            .status("IDLE")
            .battery(100.0)
            .build();
    }
}
```

## 4. 테스트 계층화 전략

| 종류 | 주요 애노테이션 | 설명 및 활용 |
| :--- | :--- | :--- |
| **단위 (Unit)** | `@ExtendWith(MockitoExtension.class)` | 스프링 컨텍스트를 띄우지 않아 수천 개의 테스트도 단숨에 실행됩니다. 비즈니스 로직(Service) 검증 시 1순위로 채택. |
| **컨트롤러 (Web)** | `@WebMvcTest(TargetController.class)` | 보안 세팅(Security), DTO Validator 방어, HTTP 상태 및 URL 매핑 검증 전용. 내부에 주입되는 Service는 무조건 `@MockBean`으로 처리. |
| **통합 (DB/Infra)** | `@SpringBootTest` + `@Transactional` | 실제 애플리케이션의 설정과 DB 커넥션, Kafka 프로듀서를 끌어올려 통합 동작을 검증. `@Transactional`로 롤백을 켭니다. 무거우므로 꼭 필요한 Repository나 통합 흐름 검증에 한정! |
