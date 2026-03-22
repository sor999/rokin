package com.robot.fleet.listener;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.robot.fleet.dto.AckDto;
import com.robot.fleet.dto.TelemetryEventDto;
import com.robot.fleet.service.TelemetryStreamService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class TelemetryKafkaListener {

    private final TelemetryStreamService streamService;
    private final SimpMessagingTemplate messagingTemplate;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = "telemetry.pose", groupId = "api-sse-broadcaster")
    public void onPose(String payload) {
        try {
            JsonNode node = objectMapper.readTree(payload);
            streamService.broadcast(TelemetryEventDto.of(
                    "pose",
                    node.get("robot_id").asText(),
                    OffsetDateTime.parse(node.get("timestamp").asText()),
                    Map.of("x", node.get("data").get("x").asDouble(), "y", node.get("data").get("y").asDouble())
            ));
        } catch (Exception e) {
            log.warn("[Listener] pose 파싱 실패: {}", e.getMessage());
        }
    }

    @KafkaListener(topics = "telemetry.battery", groupId = "api-sse-broadcaster")
    public void onBattery(String payload) {
        try {
            JsonNode node = objectMapper.readTree(payload);
            streamService.broadcast(TelemetryEventDto.of(
                    "battery",
                    node.get("robot_id").asText(),
                    OffsetDateTime.parse(node.get("timestamp").asText()),
                    Map.of("level", node.get("data").get("level").asDouble())
            ));
        } catch (Exception e) {
            log.warn("[Listener] battery 파싱 실패: {}", e.getMessage());
        }
    }

    @KafkaListener(topics = "telemetry.status", groupId = "api-sse-broadcaster")
    public void onStatus(String payload) {
        try {
            JsonNode node = objectMapper.readTree(payload);
            streamService.broadcast(TelemetryEventDto.of(
                    "status",
                    node.get("robot_id").asText(),
                    OffsetDateTime.parse(node.get("timestamp").asText()),
                    Map.of("state", node.get("data").get("state").asText())
            ));
        } catch (Exception e) {
            log.warn("[Listener] status 파싱 실패: {}", e.getMessage());
        }
    }

    @KafkaListener(topics = "ack.robot", groupId = "api-ack-broadcaster")
    public void onAck(String payload) {
        try {
            JsonNode node = objectMapper.readTree(payload);
            String robotId = node.get("robot_id").asText();
            AckDto ack = AckDto.of(
                    node.get("cmd_id").asText(),
                    robotId,
                    node.get("status").asText(),
                    node.has("message") ? node.get("message").asText() : null,
                    OffsetDateTime.parse(node.get("timestamp").asText())
            );
            // WebSocket으로 해당 로봇 Ack 구독자에게 브로드캐스팅
            messagingTemplate.convertAndSend("/topic/ack/" + robotId, ack);
            log.info("[Listener] Ack 브로드캐스트 → /topic/ack/{} | status={}", robotId, ack.status());
        } catch (Exception e) {
            log.warn("[Listener] ack 파싱 실패: {}", e.getMessage());
        }
    }
}
