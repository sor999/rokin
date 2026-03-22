package com.robot.fleet.dto;

import java.time.OffsetDateTime;

public record RobotStatusDto(
        String robotId,
        boolean online,
        OffsetDateTime lastSeen,
        PoseDto pose,
        BatteryDto battery,
        String state
) {
    public static RobotStatusDto of(String robotId, boolean online, OffsetDateTime lastSeen,
                                    PoseDto pose, BatteryDto battery, String state) {
        return new RobotStatusDto(robotId, online, lastSeen, pose, battery, state);
    }
}
