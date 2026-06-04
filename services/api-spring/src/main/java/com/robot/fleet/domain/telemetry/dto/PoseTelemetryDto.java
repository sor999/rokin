package com.robot.fleet.domain.telemetry.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;

/**
 * Pose SSE 이벤트.
 */
public record PoseTelemetryDto(
        String type,
        String robotId,
        @JsonProperty("timestamp")
        OffsetDateTime occurredAt,
        PoseTelemetryDataDto data
) implements TelemetryEvent {

    public static PoseTelemetryDto of(
            String robotId,
            OffsetDateTime occurredAt,
            double x,
            double y
    ) {
        return new PoseTelemetryDto("pose", robotId, occurredAt, new PoseTelemetryDataDto(x, y));
    }
}
