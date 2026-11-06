package com.robot.fleet.domain.telemetry.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.robot.fleet.domain.presence.service.PresenceService;
import com.robot.fleet.domain.realtime.RealtimePublisher;
import com.robot.fleet.domain.realtime.RealtimeValidation;
import com.robot.fleet.domain.telemetry.dto.BatteryTelemetryDto;
import com.robot.fleet.domain.telemetry.dto.BatteryTelemetryMessageDto;
import com.robot.fleet.domain.telemetry.dto.PoseTelemetryDataDto;
import com.robot.fleet.domain.telemetry.dto.PoseTelemetryDto;
import com.robot.fleet.domain.telemetry.dto.PoseTelemetryMessageDto;
import com.robot.fleet.domain.telemetry.dto.StatusTelemetryDto;
import com.robot.fleet.domain.telemetry.dto.StatusTelemetryMessageDto;
import com.robot.fleet.domain.telemetry.dto.TelemetryEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.function.Function;

@Slf4j
@Component
@RequiredArgsConstructor
public class TelemetryKafkaListener {

    private final RealtimePublisher publisher;
    private final ObjectMapper objectMapper;
    private final PresenceService presence;

    private <T> void handleTelemetryMessage(
            String payload,
            String type,
            Class<T> messageType,
            Function<T, TelemetryEvent> mapper
    ) {
        TelemetryEvent event;
        String eventId;
        try {
            var json = objectMapper.readTree(payload);
            RealtimeValidation.data(type, json.path("data"));
            eventId = json.path("event_id").asText();
            UUID.fromString(eventId);
            T message = objectMapper.treeToValue(json, messageType);
            event = mapper.apply(message);
            if (event.robotId() == null || !event.robotId().matches("[A-Za-z][A-Za-z0-9_-]{0,127}") || event.occurredAt() == null || event.data() == null) throw new IllegalArgumentException("Invalid telemetry");

        } catch (Exception e) {
            log.warn("[Listener] Invalid {} message: {}", type, e.getClass().getSimpleName());
            return;
        }
        if (!presence.observe(event)) return;
        publisher.telemetry(eventId, event);
    }

    @KafkaListener(topics = "telemetry.pose", groupId = "api-sse-broadcaster")
    public void handlePoseTelemetry(String payload) {
        handleTelemetryMessage(payload, "pose", PoseTelemetryMessageDto.class, message -> new PoseTelemetryDto(
                "pose", message.robotId(), message.occurredAt(), message.runtimeId(), new PoseTelemetryDataDto(message.data().x(), message.data().y())
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
