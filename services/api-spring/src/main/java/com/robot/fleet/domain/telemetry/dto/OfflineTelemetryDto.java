package com.robot.fleet.domain.telemetry.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;

/**
 * Offline SSE 이벤트.
 */
public record OfflineTelemetryDto(
        String type,
        String robotId,
        @JsonProperty("timestamp")
        OffsetDateTime occurredAt,
        StatusTelemetryDataDto data
) implements TelemetryEvent {

    public static OfflineTelemetryDto of(
            String robotId,
            OffsetDateTime occurredAt
    ) {
        return new OfflineTelemetryDto("offline", robotId, occurredAt, new StatusTelemetryDataDto("offline"));
    }
}
