package com.robot.fleet.domain.ack.listener;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.robot.fleet.domain.ack.dto.AckDto;
import com.robot.fleet.domain.ack.dto.AckMessageDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class RobotAckKafkaListener {

    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "ack.robot", groupId = "api-ack-broadcaster")
    public void handleRobotAck(String payload) {
        try {
            AckMessageDto message = objectMapper.readValue(payload, AckMessageDto.class);
            AckDto ack = AckDto.of(
                    message.cmdId(),
                    message.robotId(),
                    message.status(),
                    message.message(),
                    message.occurredAt()
            );
            messagingTemplate.convertAndSend("/topic/ack/" + message.robotId(), ack);
            log.info("[Listener] Ack 브로드캐스트 -> /topic/ack/{} | status={}", message.robotId(), ack.status());
        } catch (Exception e) {
            log.warn("[Listener] ack 메시지 처리 실패: {}", e.getMessage());
        }
    }
}
