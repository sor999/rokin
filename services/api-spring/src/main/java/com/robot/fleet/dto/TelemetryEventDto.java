package com.robot.fleet.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;
import java.util.Map;

// SSE 이벤트 페이로드: type = "pose" | "battery" | "status" | "offline"
public record TelemetryEventDto(
        String type,
        String robotId,
        @JsonProperty("timestamp")
        OffsetDateTime occurredAt,
        Map<String, Object> data
) {
    public static TelemetryEventDto of(String type, String robotId, OffsetDateTime occurredAt, Map<String, Object> data) {
        return new TelemetryEventDto(type, robotId, occurredAt, data);
    }
}
