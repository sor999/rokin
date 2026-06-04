package com.robot.fleet.domain.telemetry.dto;

/**
 * Status 텔레메트리 payload.
 */
public record StatusTelemetryDataDto(
        String state
) implements TelemetryEventData {
}
