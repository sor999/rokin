package com.robot.fleet.controller;

import com.robot.fleet.dto.CommandRequestDto;
import com.robot.fleet.service.CommandService;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.stereotype.Controller;

import java.util.Map;

@Controller
@RequiredArgsConstructor
public class CommandController {

    private final CommandService commandService;

    // WebSocket STOMP: /app/command 로 CommandRequestDto 전송
    // 응답은 /topic/cmd-result 로 브로드캐스팅 (cmdId 반환)
    @MessageMapping("/command")
    @SendTo("/topic/cmd-result")
    public Map<String, String> handleCommand(CommandRequestDto request) {
        String cmdId = commandService.sendCommand(request);
        return Map.of("cmdId", cmdId, "robotId", request.getRobotId(), "status", "accepted");
    }
}
