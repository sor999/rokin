package com.robot.fleet.domain.telemetry.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.robot.fleet.domain.simulation.service.SimulationService;
import com.robot.fleet.domain.telemetry.dto.*;
import com.robot.fleet.domain.telemetry.service.*;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.*;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.OffsetDateTime;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TelemetryRuntimeTest {
    @Test @DisplayName("미래 시각의 이전 실행 이벤트가 있어도 현재 실행의 수신으로 온라인을 복구한다")
    void broadcast_UsesReceiveTimeAndRejectsRetiredRuntime() throws Exception {
        // given
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        var stream = new TelemetryStreamService(mapper, new SimpleMeterRegistry());
        var offline = new OfflineDetectorService(stream);
        ReflectionTestUtils.setField(offline, "thresholdSeconds", 30L);
        var simulations = mock(SimulationService.class);
        when(simulations.acceptsTelemetry("robot", "current")).thenReturn(true);
        var listener = new TelemetryKafkaListener(stream, mapper, simulations);
        stream.broadcast(new PoseTelemetryDto("pose", "robot", OffsetDateTime.now().plusYears(1), "old", new PoseTelemetryDataDto(99, 99)));
        // when
        String current = "{\"robot_id\":\"robot\",\"timestamp\":\"2020-01-01T00:00:00Z\",\"simulation_runtime_id\":\"current\",\"data\":{\"x\":1,\"y\":2}}";
        listener.handlePoseTelemetry(current);
        listener.handlePoseTelemetry(current.replace("current", "old"));
        // then
        assertThat(stream.getRuntimeId("robot")).isEqualTo("current");
        assertThat(offline.isOffline("robot", "current")).isFalse();
        assertThat(stream.getLastSeenMap().get("robot")).isBetween(OffsetDateTime.now().minusSeconds(5), OffsetDateTime.now());
    }
}
