package com.robot.fleet.domain.telemetry.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.robot.fleet.domain.presence.service.PresenceService;
import com.robot.fleet.domain.realtime.RealtimePublisher;
import com.robot.fleet.domain.simulation.service.SimulationService;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessResourceFailureException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TelemetryRuntimeTest {
    private final RealtimePublisher publisher = mock(RealtimePublisher.class);
    private final PresenceService presence = mock(PresenceService.class);
    private final SimulationService simulations = mock(SimulationService.class);
    private final TelemetryKafkaListener listener = new TelemetryKafkaListener(publisher,
            new ObjectMapper().findAndRegisterModules(), presence);
    private final String json = """
            {"event_id":"0b181550-d9e4-4a66-8e0b-360d71dbece4","robot_id":"robot",
             "timestamp":"2020-01-01T00:00:00Z","simulation_runtime_id":"current","data":{"x":1,"y":2}}
            """;

    @Test @DisplayName("이전 runtime과 만료된 이벤트는 전달하지 않는다")
    void handle_FencesRuntimeAndExpiredEvents() {
        // given
        when(simulations.acceptsTelemetry("robot", "current")).thenReturn(true);
        when(presence.observe(any())).thenReturn(false);
        // when
        listener.handlePoseTelemetry(json.replace("current", "old"));
        listener.handlePoseTelemetry(json);
        // then
        verify(presence, times(2)).observe(any());
        verifyNoInteractions(publisher);
    }

    @Test @DisplayName("공유 저장소 실패는 잘못된 payload로 삼키지 않고 Kafka에 전파한다")
    void handle_PropagatesStorageFailure() {
        // given
        when(simulations.acceptsTelemetry("robot", "current")).thenReturn(true);
        when(presence.observe(any())).thenThrow(new DataAccessResourceFailureException("unavailable"));
        // when, then
        assertThatThrownBy(() -> listener.handlePoseTelemetry(json)).isInstanceOf(DataAccessResourceFailureException.class);
        verifyNoInteractions(publisher);
    }
    @Test @DisplayName("관측이 있어도 준비 보고와 heartbeat가 유효하기 전에는 SSE로 보내지 않는다")
    void handle_RequiresSharedReadinessBeforePublish() {
        // given
        when(simulations.acceptsTelemetry("robot", "current")).thenReturn(true);
        when(presence.observe(any())).thenReturn(false).thenReturn(true);
        // when
        listener.handlePoseTelemetry(json);
        // then
        verifyNoInteractions(publisher);
        // when
        listener.handlePoseTelemetry(json);
        // then
        verify(publisher).telemetry(anyString(), any());
    }

}
