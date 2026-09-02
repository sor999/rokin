package com.robot.fleet.domain.telemetry.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;

public record BatteryDto(float level, @JsonProperty("timestamp") OffsetDateTime occurredAt) {

    public static BatteryDto of(float level, OffsetDateTime occurredAt) {
        return new BatteryDto(level, occurredAt);
    }
}
