package com.robot.fleet.domain.telemetry.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;

/**
 * Kafka status 텔레메트리 메시지.
 */
public record StatusTelemetryMessageDto(
        String type,
        @JsonProperty("robot_id")
        String robotId,
        @JsonProperty("timestamp")
        OffsetDateTime occurredAt,
        StatusTelemetryDataDto data
) {
}
