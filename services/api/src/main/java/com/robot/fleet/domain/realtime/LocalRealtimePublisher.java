package com.robot.fleet.domain.realtime;

import com.robot.fleet.domain.ack.dto.AckDto;
import com.robot.fleet.domain.telemetry.dto.TelemetryEvent;
import com.robot.fleet.domain.telemetry.service.TelemetryStreamService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "fleet.realtime.enabled", havingValue = "false", matchIfMissing = true)
public class LocalRealtimePublisher implements RealtimePublisher {
    private final TelemetryStreamService stream;
    private final SimpMessagingTemplate messaging;

    public void telemetry(String eventId, TelemetryEvent event) { stream.broadcast(event); }
    public void ack(AckDto event) { messaging.convertAndSend("/topic/ack/" + event.robotId(), event); }
}
