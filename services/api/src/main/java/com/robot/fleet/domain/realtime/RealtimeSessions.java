package com.robot.fleet.domain.realtime;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.handler.WebSocketHandlerDecoratorFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Component
@RequiredArgsConstructor
public class RealtimeSessions implements WebSocketHandlerDecoratorFactory {
    private final RealtimeState state;
    private final Set<WebSocketSession> pending = ConcurrentHashMap.newKeySet();
    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();
    private final ExecutorService closing = Executors.newVirtualThreadPerTaskExecutor();

    public WebSocketHandler decorate(WebSocketHandler handler) {
        return new WebSocketHandlerDecorator(handler) {
            @Override public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                var accepted = new java.util.concurrent.atomic.AtomicBoolean();
                state.ifCurrent(state.generation(), () -> {
                    if (sessions.size() < 1000) { sessions.add(session); accepted.set(true); }
                });
                if (!accepted.get()) { session.close(CloseStatus.SERVICE_RESTARTED); return; }
                super.afterConnectionEstablished(session);
            }
            @Override public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
                sessions.remove(session);
                super.afterConnectionClosed(session, status);
            }
        };
    }

    public void reset() {
        for (WebSocketSession session : sessions) {
            if (!pending.add(session)) continue;
            closing.execute(() -> {
                try { session.close(CloseStatus.SERVICE_RESTARTED); } catch (Exception ignored) { }
                finally { pending.remove(session); sessions.remove(session); }
            });
        }
    }

    @PreDestroy public void close() { reset(); closing.shutdown(); }
}
