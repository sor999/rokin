package com.robot.fleet.domain.command.dto;

public record MoveToCommandDataDto(
        double x,
        double y
) implements CommandData {
}
