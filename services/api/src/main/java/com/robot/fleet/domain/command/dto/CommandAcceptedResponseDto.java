package com.robot.fleet.domain.command.dto;

// WebSocket STOMP Command 접수 응답
public record CommandAcceptedResponseDto(
        String cmdId,
        String robotId,
        String status
) {
    public static CommandAcceptedResponseDto of(String cmdId, String robotId) {
        return new CommandAcceptedResponseDto(cmdId, robotId, "accepted");
    }
}
