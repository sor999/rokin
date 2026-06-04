package com.robot.fleet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class FleetApplicationTest {

    @Test
    @DisplayName("스프링 애플리케이션 컨텍스트를 정상적으로 로드한다.")
    void contextLoads_성공() {
    }
}
