package com.robot.fleet.domain.telemetry.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;

/**
 * Battery SSE 이벤트.
 */
public record BatteryTelemetryDto(
        String type,
        String robotId,
        @JsonProperty("timestamp")
        OffsetDateTime occurredAt,
        BatteryTelemetryDataDto data
) implements TelemetryEvent {

    public static BatteryTelemetryDto of(
            String robotId,
            OffsetDateTime occurredAt,
            double level
    ) {
        return new BatteryTelemetryDto("battery", robotId, occurredAt, new BatteryTelemetryDataDto(level));
    }
}
