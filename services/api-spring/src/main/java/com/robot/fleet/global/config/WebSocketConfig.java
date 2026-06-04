package com.robot.fleet.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // /topic/** 은 인메모리 브로커가 구독자에게 브로드캐스팅
        registry.enableSimpleBroker("/topic");
        // 클라이언트 → 서버 전송은 /app 접두사
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // WebSocket 핸드셰이크 엔드포인트: 구체적인 도메인으로 출처 검증 강화 (프로덕션 환경에 맞게 수정 필요)
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("http://localhost:[*]")
                .withSockJS();
    }
}
