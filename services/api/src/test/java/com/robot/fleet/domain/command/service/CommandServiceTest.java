package com.robot.fleet.domain.command.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.robot.fleet.domain.command.dto.CommandRequestDto;
import com.robot.fleet.domain.command.dto.StopCommandDataDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

@ExtendWith(MockitoExtension.class)
class CommandServiceTest {
    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @Test
    @DisplayName("명령에 서버의 Fleet 식별자와 cmd_id를 포함해 로봇 키로 발행한다")
    void sendCommand_IncludesFleetOwnership() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        CommandService service = new CommandService(kafkaTemplate, mapper, "site-a");
        CommandRequestDto request = new CommandRequestDto();
        request.setRobotId("robot_1");
        request.setCommand("stop");
        request.setData(new StopCommandDataDto());
        given(kafkaTemplate.send(eq("cmd.robot"), eq("robot_1"), anyString()))
                .willReturn(CompletableFuture.completedFuture(null));

        String cmdId = service.sendCommand(request);

        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        then(kafkaTemplate).should().send(eq("cmd.robot"), eq("robot_1"), payload.capture());
        JsonNode json = mapper.readTree(payload.getValue());
        assertThat(json.get("fleet_id").asText()).isEqualTo("site-a");
        assertThat(json.get("cmd_id").asText()).isEqualTo(cmdId);
        assertThat(json.get("command").asText()).isEqualTo("stop");
        assertThat(json.get("data").isObject()).isTrue();
    }

    @Test
    @DisplayName("빈 Fleet 식별자나 consumer group 구분을 깨는 식별자를 거부한다")
    void constructor_RejectsInvalidFleetId() {
        for (String fleet : new String[]{"", "site.a", " ", "x".repeat(129)}) {
            assertThatThrownBy(() -> new CommandService(kafkaTemplate, new ObjectMapper(), fleet))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }
}
