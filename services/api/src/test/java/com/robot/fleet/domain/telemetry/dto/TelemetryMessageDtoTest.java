package com.robot.fleet.domain.telemetry.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class TelemetryMessageDtoTest {

    private final ObjectMapper objectMapper = JsonMapper.builder()
            .findAndAddModules()
            .build();

    @Test
    @DisplayName("Kafka pose 메시지를 DTO로 역직렬화한다.")
    void poseTelemetryMessageDto_역직렬화_성공() throws Exception {
        // given
        String payload = """
                {
                  "type": "pose",
                  "timestamp": "2026-04-21T05:00:00Z",
                  "robot_id": "robot_1",
                  "data": {
                    "x": 1.25,
                    "y": 3.5
                  }
                }
                """;

        // when
        PoseTelemetryMessageDto message = objectMapper.readValue(payload, PoseTelemetryMessageDto.class);

        // then
        assertThat(message.type()).isEqualTo("pose");
        assertThat(message.robotId()).isEqualTo("robot_1");
        assertThat(message.occurredAt()).isEqualTo(OffsetDateTime.parse("2026-04-21T05:00:00Z"));
        assertThat(message.data()).isEqualTo(new PoseTelemetryDataDto(1.25, 3.5));
    }

    @Test
    @DisplayName("Kafka battery 메시지를 DTO로 역직렬화한다.")
    void batteryTelemetryMessageDto_역직렬화_성공() throws Exception {
        // given
        String payload = """
                {
                  "type": "battery",
                  "timestamp": "2026-04-21T05:00:00Z",
                  "robot_id": "robot_2",
                  "data": {
                    "level": 84.5
                  }
                }
                """;

        // when
        BatteryTelemetryMessageDto message = objectMapper.readValue(payload, BatteryTelemetryMessageDto.class);

        // then
        assertThat(message.type()).isEqualTo("battery");
        assertThat(message.robotId()).isEqualTo("robot_2");
        assertThat(message.occurredAt()).isEqualTo(OffsetDateTime.parse("2026-04-21T05:00:00Z"));
        assertThat(message.data()).isEqualTo(new BatteryTelemetryDataDto(84.5));
    }

    @Test
    @DisplayName("Kafka status 메시지를 DTO로 역직렬화한다.")
    void statusTelemetryMessageDto_역직렬화_성공() throws Exception {
        // given
        String payload = """
                {
                  "type": "status",
                  "timestamp": "2026-04-21T05:00:00Z",
                  "robot_id": "robot_3",
                  "data": {
                    "state": "moving"
                  }
                }
                """;

        // when
        StatusTelemetryMessageDto message = objectMapper.readValue(payload, StatusTelemetryMessageDto.class);

        // then
        assertThat(message.type()).isEqualTo("status");
        assertThat(message.robotId()).isEqualTo("robot_3");
        assertThat(message.occurredAt()).isEqualTo(OffsetDateTime.parse("2026-04-21T05:00:00Z"));
        assertThat(message.data()).isEqualTo(new StatusTelemetryDataDto("moving"));
    }
}
