package com.robot.fleet.domain.ack.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;

/**
 * Kafka command ack 메시지.
 */
public record AckMessageDto(
        @JsonProperty("cmd_id")
        String cmdId,
        @JsonProperty("robot_id")
        String robotId,
        String status,
        String message,
        @JsonProperty("timestamp")
        OffsetDateTime occurredAt
) {
}
