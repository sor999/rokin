package com.robot.fleet.domain.command.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CommandRequestDtoTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void moveToCommandRequestDto_역직렬화_성공() throws Exception {
        String json = """
                {
                  "robotId": "robot_1",
                  "command": "move_to",
                  "data": {
                    "x": 12.5,
                    "y": -3.25
                  },
                  "issuedBy": "dashboard"
                }
                """;

        CommandRequestDto request = objectMapper.readValue(json, CommandRequestDto.class);

        assertThat(request.getRobotId()).isEqualTo("robot_1");
        assertThat(request.getCommand()).isEqualTo("move_to");
        assertThat(request.getData()).isEqualTo(new MoveToCommandDataDto(12.5, -3.25));
        assertThat(request.getIssuedBy()).isEqualTo("dashboard");
    }

    @Test
    void stopCommandRequestDto_역직렬화_성공() throws Exception {
        String json = """
                {
                  "robotId": "robot_1",
                  "command": "stop",
                  "data": {},
                  "issuedBy": "dashboard"
                }
                """;

        CommandRequestDto request = objectMapper.readValue(json, CommandRequestDto.class);

        assertThat(request.getRobotId()).isEqualTo("robot_1");
        assertThat(request.getCommand()).isEqualTo("stop");
        assertThat(request.getData()).isEqualTo(new StopCommandDataDto());
        assertThat(request.getIssuedBy()).isEqualTo("dashboard");
    }
}
