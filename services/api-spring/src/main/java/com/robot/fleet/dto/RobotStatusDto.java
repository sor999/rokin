package com.robot.fleet.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.OffsetDateTime;

public record RobotStatusDto(
        String robotId,
        boolean online,
        OffsetDateTime lastSeen,
        @Schema(nullable = true) PoseDto pose,
        @Schema(nullable = true) BatteryDto battery,
        String state
) {
    public static RobotStatusDto of(String robotId, boolean online, OffsetDateTime lastSeen,
                                    PoseDto pose, BatteryDto battery, String state) {
        return new RobotStatusDto(robotId, online, lastSeen, pose, battery, state);
    }
}
