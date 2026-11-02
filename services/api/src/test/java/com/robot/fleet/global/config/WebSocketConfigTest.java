package com.robot.fleet.global.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.mock.web.MockServletContext;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.socket.messaging.StompSubProtocolHandler;
import org.springframework.web.socket.messaging.SubProtocolWebSocketHandler;

import static org.assertj.core.api.Assertions.assertThat;

class WebSocketConfigTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("실험 환경의 수신 순서 설정이 실제 STOMP 핸들러에 적용된다")
    void preserveReceiveOrder_ConfiguresStompHandler(boolean enabled) {
        // given
        try (var context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            TestPropertyValues.of("fleet.websocket.preserve-receive-order=" + enabled).applyTo(context);
            context.register(WebSocketConfig.class);

            // when
            context.refresh();
            var handler = context.getBean("subProtocolWebSocketHandler", SubProtocolWebSocketHandler.class);
            var stomp = handler.getProtocolHandlers().stream()
                    .filter(StompSubProtocolHandler.class::isInstance)
                    .map(StompSubProtocolHandler.class::cast).findFirst().orElseThrow();

            // then
            assertThat(stomp.isPreserveReceiveOrder()).isEqualTo(enabled);
        }
    }
}
