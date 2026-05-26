package com.robot.fleet.domain.telemetry.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.OffsetDateTime;

public record PoseDto(double x, double y, @JsonProperty("timestamp") OffsetDateTime occurredAt) {

    public static PoseDto of(double x, double y, OffsetDateTime occurredAt) {
        return new PoseDto(x, y, occurredAt);
    }
}
