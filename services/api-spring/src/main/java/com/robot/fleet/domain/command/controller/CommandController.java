package com.robot.fleet.domain.command.controller;

import com.robot.fleet.domain.command.dto.CommandAcceptedResponseDto;
import com.robot.fleet.domain.command.dto.CommandRequestDto;
import com.robot.fleet.domain.command.service.CommandService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.stereotype.Controller;

@Controller
@RequiredArgsConstructor
@Slf4j
public class CommandController {

    private final CommandService commandService;

    // WebSocket STOMP: /app/command 로 CommandRequestDto 전송
    // 응답은 /topic/cmd-result 로 브로드캐스팅 (cmdId 반환)
    @MessageMapping("/command")
    @SendTo("/topic/cmd-result")
    public CommandAcceptedResponseDto handleCommand(CommandRequestDto request) {
        log.info(
                "[Command] STOMP 수신 | robotId={} command={} data={}",
                request.getRobotId(),
                request.getCommand(),
                request.getData()
        );
        String cmdId = commandService.sendCommand(request);
        return CommandAcceptedResponseDto.of(cmdId, request.getRobotId());
    }
}
