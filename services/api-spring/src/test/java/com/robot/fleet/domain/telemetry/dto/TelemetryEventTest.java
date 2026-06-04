package com.robot.fleet.domain.telemetry.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class TelemetryEventTest {

    @Test
    @DisplayName("Pose 이벤트를 정상적으로 생성한다.")
    void poseTelemetryEventDto_of_성공() {
        // given
        OffsetDateTime occurredAt = OffsetDateTime.parse("2026-04-21T05:00:00+09:00");

        // when
        PoseTelemetryDto event = PoseTelemetryDto.of("robot_1", occurredAt, 1.25, 3.5);

        // then
        assertThat(event.type()).isEqualTo("pose");
        assertThat(event.robotId()).isEqualTo("robot_1");
        assertThat(event.occurredAt()).isEqualTo(occurredAt);
        assertThat(event.data()).isEqualTo(new PoseTelemetryDataDto(1.25, 3.5));
    }

    @Test
    @DisplayName("Battery 이벤트를 정상적으로 생성한다.")
    void batteryTelemetryEventDto_of_성공() {
        // given
        OffsetDateTime occurredAt = OffsetDateTime.parse("2026-04-21T05:00:00+09:00");

        // when
        BatteryTelemetryDto event = BatteryTelemetryDto.of("robot_2", occurredAt, 84.5);

        // then
        assertThat(event.type()).isEqualTo("battery");
        assertThat(event.robotId()).isEqualTo("robot_2");
        assertThat(event.occurredAt()).isEqualTo(occurredAt);
        assertThat(event.data()).isEqualTo(new BatteryTelemetryDataDto(84.5));
    }

    @Test
    @DisplayName("Status 이벤트를 정상적으로 생성한다.")
    void statusTelemetryEventDto_of_성공() {
        // given
        OffsetDateTime occurredAt = OffsetDateTime.parse("2026-04-21T05:00:00+09:00");

        // when
        StatusTelemetryDto event = StatusTelemetryDto.of("robot_3", occurredAt, "moving");

        // then
        assertThat(event.type()).isEqualTo("status");
        assertThat(event.robotId()).isEqualTo("robot_3");
        assertThat(event.occurredAt()).isEqualTo(occurredAt);
        assertThat(event.data()).isEqualTo(new StatusTelemetryDataDto("moving"));
    }

    @Test
    @DisplayName("Offline 이벤트를 정상적으로 생성한다.")
    void offlineTelemetryEventDto_of_성공() {
        // given
        OffsetDateTime occurredAt = OffsetDateTime.parse("2026-04-21T05:00:00+09:00");

        // when
        OfflineTelemetryDto event = OfflineTelemetryDto.of("robot_4", occurredAt);

        // then
        assertThat(event.type()).isEqualTo("offline");
        assertThat(event.robotId()).isEqualTo("robot_4");
        assertThat(event.occurredAt()).isEqualTo(occurredAt);
        assertThat(event.data()).isEqualTo(new StatusTelemetryDataDto("offline"));
    }
}
