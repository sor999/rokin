package com.robot.fleet.domain.command.controller;

import com.robot.fleet.domain.command.dto.CommandRequestDto;
import com.robot.fleet.domain.command.dto.StopCommandDataDto;
import com.robot.fleet.domain.command.service.CommandService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

/** 정지 요청과 접수 응답의 연결을 검증한다. */
@ExtendWith(MockitoExtension.class)
class CommandControllerTest {
    @Mock
    private CommandService commandService;

    @InjectMocks
    private CommandController controller;

    @Test
    @DisplayName("정지 접수 응답에 요청 식별자를 포함하며 로봇 완료로 표시하지 않는다")
    void handleCommand_ReturnsStopRequestId() {
        // given
        CommandRequestDto request = new CommandRequestDto();
        request.setRequestId("stop-request-1");
        request.setRobotId("robot_1");
        request.setCommand("stop");
        request.setData(new StopCommandDataDto());
        given(commandService.sendCommand(request)).willReturn("stop-command-1");

        // when
        var result = controller.handleCommand(request);

        // then
        assertThat(result.requestId()).isEqualTo("stop-request-1");
        assertThat(result.cmdId()).isEqualTo("stop-command-1");
        assertThat(result.robotId()).isEqualTo("robot_1");
        assertThat(result.status()).isEqualTo("accepted");
        then(commandService).should().sendCommand(request);
    }
}
