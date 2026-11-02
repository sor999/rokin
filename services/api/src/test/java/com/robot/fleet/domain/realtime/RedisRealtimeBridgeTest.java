package com.robot.fleet.domain.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.robot.fleet.domain.telemetry.service.TelemetryStreamService;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;

import static org.mockito.Mockito.*;

class RedisRealtimeBridgeTest {
    @Test @DisplayName("Redis close가 실패해도 발행 연결과 화면 세션 및 캐시를 정리한다")
    @SuppressWarnings("unchecked")
    void invalidate_CleansSessionsWhenConnectionCloseThrows() throws Exception {
        // given
        var state = new RealtimeState(true); state.activate(0);
        var stream = mock(TelemetryStreamService.class);
        var sessions = mock(RealtimeSessions.class);
        var bridge = new RedisRealtimeBridge("localhost", 6379, "", "default", new ObjectMapper(),
                mock(RealtimeReceiver.class), state, stream, sessions, new SimpleMeterRegistry());
        var publisher = mock(StatefulRedisConnection.class);
        var subscriber = mock(StatefulRedisPubSubConnection.class);
        doThrow(new IllegalStateException("close failed")).when(subscriber).close();
        var constructor = Class.forName(RedisRealtimeBridge.class.getName() + "$Connections").getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        ReflectionTestUtils.setField(bridge, "connections", constructor.newInstance(0L, publisher, subscriber));
        try {
            // when
            ReflectionTestUtils.invokeMethod(bridge, "invalidate", 0L);
            // then
            verify(stream, timeout(2000)).reset();
            verify(sessions, timeout(2000)).reset();
            verify(publisher).close();
            org.assertj.core.api.Assertions.assertThat(state.available()).isFalse();
        } finally {
            bridge.stop();
        }
    }
}
