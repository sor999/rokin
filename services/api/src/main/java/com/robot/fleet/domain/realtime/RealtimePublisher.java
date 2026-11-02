package com.robot.fleet.domain.realtime;

import com.robot.fleet.domain.ack.dto.AckDto;
import com.robot.fleet.domain.telemetry.dto.TelemetryEvent;

public interface RealtimePublisher {
    void telemetry(String eventId, TelemetryEvent event);
    void ack(AckDto event);
}
