package com.robot.fleet.domain.command.dto;

public record CommandAcceptedResponseDto(String cmdId,String robotId,String status,String requestId,String errorCode,String message) {
    public static CommandAcceptedResponseDto of(String cmdId,String robotId,String requestId) {
        return new CommandAcceptedResponseDto(cmdId,robotId,"accepted",requestId,null,null);
    }
    public static CommandAcceptedResponseDto rejected(CommandRequestDto request,String code,String message) {
        return new CommandAcceptedResponseDto(null,request.getRobotId(),"rejected",request.getRequestId(),code,message);
    }
}
