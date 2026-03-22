package com.robot.fleet.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;

// WebSocket Ack 응답 (Kafka → WS 브로드캐스트)
public record AckDto(
        String cmdId,
        String robotId,
        String status,
        String message,
        @JsonProperty("timestamp")
        OffsetDateTime occurredAt
) {
    public static AckDto of(String cmdId, String robotId, String status, String message, OffsetDateTime occurredAt) {
        return new AckDto(cmdId, robotId, status, message, occurredAt);
    }
}
