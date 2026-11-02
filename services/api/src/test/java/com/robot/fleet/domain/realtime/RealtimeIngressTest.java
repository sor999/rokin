package com.robot.fleet.domain.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.robot.fleet.domain.simulation.service.SimulationService;
import com.robot.fleet.domain.telemetry.listener.TelemetryKafkaListener;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RealtimeIngressTest {
    @Test @DisplayName("전달 경로 실패를 Kafka에 전파하고 잘못된 payload는 발행하지 않는다")
    void telemetry_RetriesTransportFailureAndDropsMalformedInput() {
        // given
        var publisher = mock(RealtimePublisher.class);
        var simulations = mock(SimulationService.class);
        when(simulations.acceptsTelemetry("robot_1", null)).thenReturn(true);
        var listener = new TelemetryKafkaListener(publisher, new ObjectMapper().findAndRegisterModules(), simulations);
        String json = "{\"event_id\":\"0b181550-d9e4-4a66-8e0b-360d71dbece4\",\"robot_id\":\"robot_1\",\"timestamp\":\"2026-11-02T00:00:00Z\",\"data\":{\"x\":1,\"y\":2}}";
        doThrow(new IllegalStateException("transport unavailable")).when(publisher).telemetry(anyString(), any());
        // when, then
        assertThatThrownBy(() -> listener.handlePoseTelemetry(json)).isInstanceOf(IllegalStateException.class);
        reset(publisher);
        listener.handlePoseTelemetry(json.replace("\"y\":2", "\"other\":2"));
        listener.handlePoseTelemetry("not json");
        verifyNoInteractions(publisher);
    }
}
