package com.robot.fleet.domain.command.controller;

import com.robot.fleet.domain.command.dto.CommandRequestDto;
import com.robot.fleet.domain.command.dto.StopCommandDataDto;
import com.robot.fleet.domain.command.service.CommandService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.web.server.ResponseStatusException;

import java.util.stream.Stream;

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

    @Test void invalidCommandReturnsCorrelatedRejection() {
        var request=new CommandRequestDto(); request.setRobotId("robot_1");request.setRequestId("invalid-key");
        given(commandService.sendCommand(request)).willThrow(new IllegalArgumentException("invalid UUID"));
        var response=controller.handleCommand(request);
        assertThat(response.requestId()).isEqualTo("invalid-key");assertThat(response.status()).isEqualTo("rejected");
        assertThat(response.cmdId()).isNull();assertThat(response.errorCode()).isEqualTo("INVALID_REQUEST");
    }
    @ParameterizedTest
    @MethodSource("rejectionCases")
    void commandFailuresReturnCorrelatedRejections(RuntimeException error, String expectedCode) {
        var request=new CommandRequestDto(); request.setRobotId("robot_1");request.setRequestId("failed-request");
        given(commandService.sendCommand(request)).willThrow(error);
        var result=controller.handleCommand(request);
        assertThat(result.requestId()).isEqualTo("failed-request");assertThat(result.robotId()).isEqualTo("robot_1");
        assertThat(result.status()).isEqualTo("rejected");assertThat(result.cmdId()).isNull();
        assertThat(result.errorCode()).isEqualTo(expectedCode);
        if (expectedCode.equals("STORAGE_UNAVAILABLE") || expectedCode.equals("DELIVERY_UNCERTAIN")) {
            assertThat(result.message()).contains("자동으로 다시 보내지 마세요").doesNotContain("조회");
        }
    }
    static Stream<Arguments> rejectionCases() {
        return Stream.of(
            Arguments.of(new DataAccessResourceFailureException("db down"),"STORAGE_UNAVAILABLE"),
            Arguments.of(new TransactionSystemException("commit uncertain"),"STORAGE_UNAVAILABLE"),
            Arguments.of(new ResponseStatusException(HttpStatus.CONFLICT,"conflict"),"IDEMPOTENCY_CONFLICT"),
            Arguments.of(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"offline"),"COMMAND_UNAVAILABLE"),
            Arguments.of(new IllegalStateException("send failure"),"DELIVERY_UNCERTAIN")
        );
    }
    @Test void keyedDurableStorageFailureProvidesSameKeyLookupOnly() {
        var request=new CommandRequestDto(); request.setRobotId("robot_1");request.setRequestId("keyed");
        request.setIdempotencyKey("00000000-0000-0000-0000-000000000041");
        given(commandService.sendCommand(request)).willThrow(new DataAccessResourceFailureException("db"));
        given(commandService.durableEnabled()).willReturn(true);
        var result=controller.handleCommand(request);
        assertThat(result.errorCode()).isEqualTo("STORAGE_UNAVAILABLE");
        assertThat(result.message()).contains("동일한 idempotencyKey", "새 키로 다시 보내지 마세요");
    }
    @Test void keyDoesNotEnableLookupForLegacyCommands() {
        var request=new CommandRequestDto(); request.setRobotId("robot_1");request.setIdempotencyKey("key");
        given(commandService.sendCommand(request)).willThrow(new DataAccessResourceFailureException("db"));
        var result=controller.handleCommand(request);
        assertThat(result.message()).doesNotContain("조회").contains("불확실");
    }
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
