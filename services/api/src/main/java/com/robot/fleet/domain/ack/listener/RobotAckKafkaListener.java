package com.robot.fleet.domain.ack.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.robot.fleet.domain.ack.dto.AckDto;
import com.robot.fleet.domain.ack.dto.AckMessageDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import com.robot.fleet.domain.realtime.RealtimePublisher;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class RobotAckKafkaListener {

    private final RealtimePublisher publisher;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "ack.robot", groupId = "api-ack-broadcaster")
    public void handleRobotAck(String payload) {
        AckDto ack;
        try {
            AckMessageDto message = objectMapper.readValue(payload, AckMessageDto.class);
            java.util.UUID.fromString(message.cmdId());
            if (message.robotId() == null || !message.robotId().matches("[A-Za-z][A-Za-z0-9_-]{0,127}") || message.occurredAt() == null
                    || message.status() == null || !java.util.List.of("accepted", "done", "failed").contains(message.status())) throw new IllegalArgumentException("Invalid ack");
            ack = AckDto.of(
                    message.cmdId(),
                    message.robotId(),
                    message.status(),
                    message.message(),
                    message.occurredAt()
            );
        } catch (Exception e) {
            log.warn("[Listener] Invalid ack: {}", e.getClass().getSimpleName());
            return;
        }
        publisher.ack(ack);
    }
}
