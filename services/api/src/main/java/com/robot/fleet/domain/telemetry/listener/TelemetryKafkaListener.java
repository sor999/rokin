package com.robot.fleet.domain.telemetry.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.robot.fleet.domain.telemetry.dto.BatteryTelemetryMessageDto;
import com.robot.fleet.domain.telemetry.dto.BatteryTelemetryDto;
import com.robot.fleet.domain.telemetry.dto.PoseTelemetryMessageDto;
import com.robot.fleet.domain.telemetry.dto.PoseTelemetryDto;
import com.robot.fleet.domain.telemetry.dto.StatusTelemetryMessageDto;
import com.robot.fleet.domain.telemetry.dto.StatusTelemetryDto;
import com.robot.fleet.domain.telemetry.dto.TelemetryEvent;
import com.robot.fleet.domain.telemetry.service.TelemetryStreamService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.function.Function;

@Slf4j
@Component
@RequiredArgsConstructor
public class TelemetryKafkaListener {

    private final TelemetryStreamService streamService;
    private final ObjectMapper objectMapper;
    private final com.robot.fleet.domain.simulation.service.SimulationService simulations;

    private <T> void handleTelemetryMessage(
            String payload,
            String type,
            Class<T> messageType,
            Function<T, TelemetryEvent> mapper
    ) {
        try {
            T message = objectMapper.readValue(payload, messageType);
            TelemetryEvent event = mapper.apply(message);
            if (!simulations.acceptsTelemetry(event.robotId(), event.runtimeId())) return;
            streamService.broadcast(event);
            log.debug("[Listener] {} SSE 브로드캐스트 완료 | robotId={}", type, event.robotId());
        } catch (Exception e) {
            log.warn("[Listener] {} 메시지 처리 실패: {}", type, e.getMessage());
        }
    }

    @KafkaListener(topics = "telemetry.pose", groupId = "api-sse-broadcaster")
    public void handlePoseTelemetry(String payload) {
        handleTelemetryMessage(payload, "pose", PoseTelemetryMessageDto.class, message -> new PoseTelemetryDto(
                "pose", message.robotId(), message.occurredAt(), message.runtimeId(), new com.robot.fleet.domain.telemetry.dto.PoseTelemetryDataDto(message.data().x(), message.data().y())
        ));
    }

    @KafkaListener(topics = "telemetry.battery", groupId = "api-sse-broadcaster")
    public void handleBatteryTelemetry(String payload) {
        handleTelemetryMessage(payload, "battery", BatteryTelemetryMessageDto.class, message -> new BatteryTelemetryDto(
                "battery", message.robotId(), message.occurredAt(), message.runtimeId(), message.data()
        ));
    }

    @KafkaListener(topics = "telemetry.status", groupId = "api-sse-broadcaster")
    public void handleStatusTelemetry(String payload) {
        handleTelemetryMessage(payload, "status", StatusTelemetryMessageDto.class, message -> new StatusTelemetryDto(
                "status", message.robotId(), message.occurredAt(), message.runtimeId(), message.data()
        ));
    }
}
