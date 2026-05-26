package com.robot.fleet.domain.telemetry.dto;

/**
 * Pose 텔레메트리 payload.
 */
public record PoseTelemetryDataDto(
        double x,
        double y
) implements TelemetryEventData {
}
