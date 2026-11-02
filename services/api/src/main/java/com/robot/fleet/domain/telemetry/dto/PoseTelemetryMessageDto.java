package com.robot.fleet.domain.telemetry.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;

/**
 * Kafka pose 텔레메트리 메시지.
 */
public record PoseTelemetryMessageDto(
        @JsonProperty("event_id") String eventId,
        String type,
        @JsonProperty("robot_id")
        String robotId,
        @JsonProperty("timestamp")
        OffsetDateTime occurredAt,
        @JsonProperty("simulation_runtime_id") String runtimeId,
        PoseTelemetryDataDto data
) {
}
