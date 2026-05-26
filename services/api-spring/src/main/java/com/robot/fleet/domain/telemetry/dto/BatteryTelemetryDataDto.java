package com.robot.fleet.domain.telemetry.dto;

/**
 * Battery 텔레메트리 payload.
 */
public record BatteryTelemetryDataDto(
        double level
) implements TelemetryEventData {
}
