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
        var delivery = (java.util.concurrent.ThreadPoolExecutor) ReflectionTestUtils.getField(bridge, "delivery");
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        delivery.execute(() -> {
            entered.countDown();
            try { release.await(); } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        });
        org.assertj.core.api.Assertions.assertThat(entered.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        delivery.execute(() -> { throw new AssertionError("retired queued delivery ran"); });
        try {
            // when
            ReflectionTestUtils.invokeMethod(bridge, "invalidate", 0L);
            // then
            verify(stream, timeout(2000)).reset();
            verify(sessions, timeout(2000)).reset();
            verify(publisher).close();
            org.assertj.core.api.Assertions.assertThat(delivery.getQueue()).isEmpty();
            org.assertj.core.api.Assertions.assertThat(state.available()).isFalse();
        } finally {
            release.countDown();
            bridge.stop();
        }
    }
    @Test @DisplayName("발행 envelope 상한에서 Ack를 전달하고 초과 시 Redis에 보내지 않는다")
    @SuppressWarnings("unchecked")
    void publish_EnforcesEnvelopeBoundaryBeforeRedis() throws Exception {
        var mapper = new ObjectMapper().findAndRegisterModules();
        var state = new RealtimeState(true); state.activate(0);
        var bridge = new RedisRealtimeBridge("localhost", 6379, "", "default", mapper,
                mock(RealtimeReceiver.class), state, mock(TelemetryStreamService.class), mock(RealtimeSessions.class), new SimpleMeterRegistry());
        var publisher = mock(StatefulRedisConnection.class);
        var commands = mock(io.lettuce.core.api.sync.RedisCommands.class);
        when(publisher.sync()).thenReturn(commands);
        when(commands.publish(anyString(), anyString())).thenReturn(1L);
        var subscriber = mock(StatefulRedisPubSubConnection.class);
        var constructor = Class.forName(RedisRealtimeBridge.class.getName() + "$Connections").getDeclaredConstructors()[0];
        constructor.setAccessible(true);
        ReflectionTestUtils.setField(bridge, "connections", constructor.newInstance(0L, publisher, subscriber));
        String cmd = java.util.UUID.randomUUID().toString();
        var time = java.time.OffsetDateTime.now();
        var empty = com.robot.fleet.domain.ack.dto.AckDto.of(cmd, "robot_1", "done", "", time);
        int base = mapper.writeValueAsString(new RealtimeReceiver.Envelope(1, "default", "ack", cmd + ":done", mapper.valueToTree(empty))).length();
        String message = "x".repeat(RealtimeValidation.MAX_ENVELOPE_LENGTH - base);
        try {
            bridge.ack(com.robot.fleet.domain.ack.dto.AckDto.of(cmd, "robot_1", "done", message, time));
            verify(commands).publish(eq("rokin.realtime.default"), argThat((String value) -> value.length() == RealtimeValidation.MAX_ENVELOPE_LENGTH));
            clearInvocations(commands);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> bridge.ack(
                    com.robot.fleet.domain.ack.dto.AckDto.of(cmd, "robot_1", "done", message + "x", time)))
                    .isInstanceOf(IllegalArgumentException.class);
            verifyNoInteractions(commands);
            org.assertj.core.api.Assertions.assertThat(state.available()).isTrue();
        } finally {
            bridge.stop();
        }
    }

}
