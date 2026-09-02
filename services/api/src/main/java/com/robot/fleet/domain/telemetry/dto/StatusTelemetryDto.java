package com.robot.fleet.domain.telemetry.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;

/**
 * Status SSE 이벤트.
 */
public record StatusTelemetryDto(
        String type,
        String robotId,
        @JsonProperty("timestamp")
        OffsetDateTime occurredAt,
        StatusTelemetryDataDto data
) implements TelemetryEvent {

    public static StatusTelemetryDto of(
            String robotId,
            OffsetDateTime occurredAt,
            String state
    ) {
        return new StatusTelemetryDto("status", robotId, occurredAt, new StatusTelemetryDataDto(state));
    }
}
