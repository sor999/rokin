package com.robot.fleet.domain.telemetry.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;

/**
 * Kafka battery 텔레메트리 메시지.
 */
public record BatteryTelemetryMessageDto(
        String type,
        @JsonProperty("robot_id")
        String robotId,
        @JsonProperty("timestamp")
        OffsetDateTime occurredAt,
        BatteryTelemetryDataDto data
) {
}
