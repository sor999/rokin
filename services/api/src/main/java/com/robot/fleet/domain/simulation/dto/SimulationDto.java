package com.robot.fleet.domain.simulation.dto;

import java.time.OffsetDateTime;

public record SimulationDto(String robotId, String fleetId, double startX, double startY, double speed,
                            String desiredState, long version, String state, String error,
                            String runtimeId, OffsetDateTime reportedAt) {
    public SimulationDto withState(String next, String reason) {
        return new SimulationDto(robotId, fleetId, startX, startY, speed, desiredState, version,
                next, reason, runtimeId, reportedAt);
    }
}
