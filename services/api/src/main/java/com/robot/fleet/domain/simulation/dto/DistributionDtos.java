package com.robot.fleet.domain.simulation.dto;

import java.util.List;

public final class DistributionDtos {
    private DistributionDtos() { }

    public record PulseRequest(String runtimeId, int capacity) { }

    public record ReleaseRequest(String robotId, String executionId, long assignmentVersion) { }

    public record ReleaseBatch(String runtimeId, List<ReleaseRequest> releases) { }

    public record Assignment(
            String robotId,
            String executionId,
            long assignmentVersion,
            String state,
            long version,
            String desiredState,
            double startX,
            double startY,
            double speed) { }

    public record Snapshot(
            String fleetId,
            String runtimeId,
            String state,
            long ttlMillis,
            List<Assignment> assignments) { }

    public record Server(
            String runtimeId,
            int capacity,
            String state,
            int assignedCount,
            boolean online) { }
}
