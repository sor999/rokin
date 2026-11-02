package com.robot.fleet.domain.telemetry.service;

import com.robot.fleet.domain.telemetry.dto.StatusTelemetryDto;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.net.Socket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.*;

@SpringBootTest(classes = TelemetrySocketTimeoutTest.App.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"server.tomcat.connection-timeout=200ms", "fleet.sse.send-timeout-ms=100",
                "fleet.sse.max-connections=1", "management.endpoint.health.group.readiness.include=readinessState"})
class TelemetrySocketTimeoutTest {
    @Configuration(proxyBeanMethods = false)
    @TestComponent
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class, HibernateJpaAutoConfiguration.class,
            FlywayAutoConfiguration.class, KafkaAutoConfiguration.class})
    @Import({TelemetryStreamService.class, StreamController.class})
    static class App { }

    @TestComponent
    @RestController
    static class StreamController {
        private final TelemetryStreamService stream;
        StreamController(TelemetryStreamService stream) { this.stream = stream; }
        @GetMapping(value = "/socket-test", produces = "text/event-stream")
        SseEmitter open() { return stream.createEmitter(); }
    }

    @LocalServerPort int port;
    @Autowired TelemetryStreamService stream;
    @Autowired MeterRegistry metrics;

    private void awaitGauge(String name, double expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (metrics.get(name).gauge().value() != expected && System.nanoTime() < deadline) Thread.sleep(5);
        assertThat(metrics.get(name).gauge().value()).isEqualTo(expected);
    }

    @Test @Timeout(15)
    @DisplayName("응답을 읽지 않는 TCP 클라이언트의 writer를 회수하고 새 연결을 허용한다")
    void socketTimeout_ReclaimsBlockedWriter() throws Exception {
        // given
        try (var socket = new Socket()) {
            socket.setReceiveBufferSize(1024);
            socket.setSoTimeout(3000);
            socket.connect(new InetSocketAddress("127.0.0.1", port));
            socket.getOutputStream().write("GET /socket-test HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            awaitGauge("fleet.sse.connections", 1);
            stream.sendPing();
            StringBuilder headers = new StringBuilder();
            while (!headers.toString().endsWith("\r\n\r\n")) {
                int value = socket.getInputStream().read();
                assertThat(value).isNotEqualTo(-1);
                headers.append((char) value);
            }
            // when: 작은 수신 창을 읽지 않고 큰 테스트 이벤트로 실제 소켓 쓰기를 막는다.
            stream.broadcast(StatusTelemetryDto.of("robot_1", OffsetDateTime.now(), "x".repeat(8 * 1024 * 1024)));
            // then
            awaitGauge("fleet.sse.connections", 0);
            awaitGauge("fleet.sse.writers", 0);
            assertThatCode(stream::createEmitter).doesNotThrowAnyException();
            awaitGauge("fleet.sse.writers", 1);
            stream.reset();
            awaitGauge("fleet.sse.writers", 0);
        }
    }
}
