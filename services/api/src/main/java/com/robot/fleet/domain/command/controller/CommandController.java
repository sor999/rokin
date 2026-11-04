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
        try {
            String cmdId = commandService.sendCommand(request);
            return CommandAcceptedResponseDto.of(cmdId, request.getRobotId(), request.getRequestId());
        } catch (IllegalArgumentException e) {
            return CommandAcceptedResponseDto.rejected(request,"INVALID_REQUEST","명령 입력을 확인해주세요.");
        } catch (org.springframework.web.server.ResponseStatusException e) {
            return CommandAcceptedResponseDto.rejected(request,e.getStatusCode().value()==409 ? "IDEMPOTENCY_CONFLICT" : "COMMAND_UNAVAILABLE",e.getReason());
        } catch (org.springframework.dao.DataAccessException | org.springframework.transaction.TransactionException e) {
            boolean recoverable = commandService.durableEnabled() && request.getIdempotencyKey()!=null
                    && !request.getIdempotencyKey().isBlank();
            String message = recoverable
                    ? "동일한 idempotencyKey로 접수 여부를 조회해주세요. 새 키로 다시 보내지 마세요."
                    : "명령 접수 결과가 불확실합니다. 자동으로 다시 보내지 마세요.";
            return CommandAcceptedResponseDto.rejected(request,"STORAGE_UNAVAILABLE",message);
        } catch (IllegalStateException e) {
            return CommandAcceptedResponseDto.rejected(request,"DELIVERY_UNCERTAIN",
                    "명령 전달 결과를 확인할 수 없습니다. 자동으로 다시 보내지 마세요.");
        }
    }
}
