package com.robot.fleet.domain.telemetry.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;

/**
 * SSE 텔레메트리 이벤트의 공통 계약.
 */
public sealed interface TelemetryEvent permits
        PoseTelemetryDto,
        BatteryTelemetryDto,
        StatusTelemetryDto,
        OfflineTelemetryDto {

    String type();

    String robotId();

    @JsonProperty("timestamp")
    OffsetDateTime occurredAt();

    TelemetryEventData data();
}
