package com.robot.fleet.global.config;

import com.robot.fleet.domain.realtime.RealtimeState;
import com.robot.fleet.domain.realtime.RealtimeSessions;
import org.springframework.web.socket.config.annotation.WebSocketTransportRegistration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final boolean preserveReceiveOrder;
    private final RealtimeState realtime;
    private final RealtimeSessions sessions;

    public WebSocketConfig(@Value("${fleet.websocket.preserve-receive-order:false}") boolean preserveReceiveOrder,
                           RealtimeState realtime,
                           RealtimeSessions sessions) {
        this.preserveReceiveOrder = preserveReceiveOrder;
        this.realtime = realtime;
        this.sessions = sessions;
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // /topic/** 은 인메모리 브로커가 구독자에게 브로드캐스팅
        registry.enableSimpleBroker("/topic");
        // 클라이언트 → 서버 전송은 /app 접두사
        registry.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void configureWebSocketTransport(WebSocketTransportRegistration registration) {
        registration.setSendTimeLimit(5000).setSendBufferSizeLimit(65536).setMessageSizeLimit(65536).addDecoratorFactory(sessions);
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(new ChannelInterceptor() {
            @Override public Message<?> preSend(Message<?> message,
                    MessageChannel channel) {
                var type = SimpMessageHeaderAccessor.getMessageType(message.getHeaders());
                if (type == SimpMessageType.CONNECT
                        || type == SimpMessageType.MESSAGE
                        || type == SimpMessageType.SUBSCRIBE) realtime.requireAvailable();
                return message;
            }
        });
    }

    @Override
    public void configureClientOutboundChannel(ChannelRegistration registration) {
        registration.taskExecutor().corePoolSize(4).maxPoolSize(8).queueCapacity(256);
        registration.interceptors(new ChannelInterceptor() {
            @Override public void afterSendCompletion(Message<?> message,
                    MessageChannel channel, boolean sent, Exception error) {
                if (!sent || error != null) sessions.reset();
            }
        });
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.setPreserveReceiveOrder(preserveReceiveOrder);
        // WebSocket 핸드셰이크 엔드포인트: 구체적인 도메인으로 출처 검증 강화 (프로덕션 환경에 맞게 수정 필요)
        registry.addEndpoint("/ws")
                .setAllowedOriginPatterns("http://localhost:[*]")
                .withSockJS();
    }
}
