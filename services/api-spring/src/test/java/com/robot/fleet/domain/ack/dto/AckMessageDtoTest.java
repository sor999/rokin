package com.robot.fleet.domain.ack.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class AckMessageDtoTest {

    private final ObjectMapper objectMapper = JsonMapper.builder()
            .findAndAddModules()
            .build();

    @Test
    @DisplayName("Kafka ack 메시지를 DTO로 역직렬화한다.")
    void ackMessageDto_역직렬화_성공() throws Exception {
        // given
        String payload = """
                {
                  "cmd_id": "cmd-1",
                  "timestamp": "2026-04-21T05:00:00Z",
                  "robot_id": "robot_1",
                  "status": "done",
                  "message": "arrived"
                }
                """;

        // when
        AckMessageDto message = objectMapper.readValue(payload, AckMessageDto.class);

        // then
        assertThat(message.cmdId()).isEqualTo("cmd-1");
        assertThat(message.robotId()).isEqualTo("robot_1");
        assertThat(message.status()).isEqualTo("done");
        assertThat(message.message()).isEqualTo("arrived");
        assertThat(message.occurredAt()).isEqualTo(OffsetDateTime.parse("2026-04-21T05:00:00Z"));
    }
}
