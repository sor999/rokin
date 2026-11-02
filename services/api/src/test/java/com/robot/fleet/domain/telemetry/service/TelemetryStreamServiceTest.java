package com.robot.fleet.domain.telemetry.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.robot.fleet.domain.telemetry.dto.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.*;

class TelemetryStreamServiceTest {
    static class Emitter extends SseEmitter {
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1), failed = new CountDownLatch(1);
        final List<String> values = new CopyOnWriteArrayList<>();
        final boolean slow;
        Emitter(boolean slow) { this.slow = slow; }
        @Override public void send(SseEventBuilder builder) throws IOException {
            for (var value : builder.build()) if (value.getData() instanceof String data && data.startsWith("{")) values.add(data);
            entered.countDown();
            if (slow && values.size() == 1) {
                try { release.await(); } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException(error); }
            }
        }
        @Override public void completeWithError(Throwable error) { failed.countDown(); }
        @Override public void complete() { failed.countDown(); }
    }
    private PoseTelemetryDto event(String robot, double x) {
        return new PoseTelemetryDto("pose", robot, OffsetDateTime.now(), "current", new PoseTelemetryDataDto(x, 0));
    }

    @Test @DisplayName("한 SSE 연결의 전송 지연이 다른 연결을 막지 않으며 느린 연결만 종료한다")
    void broadcast_IsolatesSlowConnection() throws Exception {
        // given
        var metrics = new SimpleMeterRegistry();
        try (var stream = new TelemetryStreamService(new ObjectMapper().findAndRegisterModules(), metrics)) {
            ReflectionTestUtils.setField(stream, "sendTimeoutMs", 100L);
            Emitter slow = new Emitter(true), healthy = new Emitter(false);
            stream.registerEmitter(slow); stream.registerEmitter(healthy);
            // when
            stream.broadcast(event("robot_1", 1));
            // then
            assertThat(healthy.entered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(slow.failed.await(2, TimeUnit.SECONDS)).isTrue();
            stream.broadcast(event("robot_1", 2));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (healthy.values.size() < 2 && System.nanoTime() < deadline) Thread.sleep(5);
            assertThat(healthy.values).hasSize(2);
            assertThat(metrics.get("fleet.sse.connections").gauge().value()).isEqualTo(1);
        }
    }

    @Test @DisplayName("막힌 연결의 같은 로봇 위치는 최신 대기 값 하나로 병합한다")
    void broadcast_CoalescesPendingPose() throws Exception {
        // given
        var metrics = new SimpleMeterRegistry();
        var mapper = new ObjectMapper().findAndRegisterModules();
        try (var stream = new TelemetryStreamService(mapper, metrics)) {
            Emitter slow = new Emitter(true);
            stream.registerEmitter(slow);
            stream.broadcast(event("robot_1", 0));
            assertThat(slow.entered.await(2, TimeUnit.SECONDS)).isTrue();
            // when
            for (int x = 1; x <= 100; x++) stream.broadcast(event("robot_1", x));
            // then
            assertThat(metrics.get("fleet.sse.pending").gauge().value()).isEqualTo(1);
            slow.release.countDown();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (slow.values.size() < 2 && System.nanoTime() < deadline) Thread.sleep(5);
            assertThat(mapper.readTree(slow.values.get(1)).path("data").path("x").asDouble()).isEqualTo(100);
            assertThat(slow.values).hasSize(2);
        }
    }

    @Test @DisplayName("서로 다른 로봇의 대기열과 동시 연결 수에 상한을 둔다")
    void broadcast_BoundsConnectionsAndBuffer() throws Exception {
        // given
        try (var stream = new TelemetryStreamService(new ObjectMapper().findAndRegisterModules(), new SimpleMeterRegistry())) {
            ReflectionTestUtils.setField(stream, "maxConnections", 1);
            ReflectionTestUtils.setField(stream, "queueCapacity", 2);
            Emitter slow = new Emitter(true);
            stream.registerEmitter(slow);
            assertThatThrownBy(() -> stream.registerEmitter(new Emitter(false))).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
            stream.broadcast(event("robot_0", 1));
            assertThat(slow.entered.await(2, TimeUnit.SECONDS)).isTrue();
            // when
            stream.broadcast(event("robot_1", 1)); stream.broadcast(event("robot_2", 1)); stream.broadcast(event("robot_3", 1));
            // then
            assertThat(slow.failed.await(2, TimeUnit.SECONDS)).isTrue();
        }
    }
}
