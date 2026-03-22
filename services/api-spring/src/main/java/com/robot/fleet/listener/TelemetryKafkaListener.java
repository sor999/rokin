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

    private void parseAndBroadcast(String payload, String type, java.util.function.Function<JsonNode, TelemetryEventDto> parser) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            TelemetryEventDto event = parser.apply(root);
            streamService.broadcast(event);
        } catch (Exception e) {
            log.warn("[Listener] {} 파싱/브로드캐스트 실패: {}", type, e.getMessage());
        }
    }

    @KafkaListener(topics = "telemetry.pose", groupId = "api-sse-broadcaster")
    public void onPose(String payload) {
        parseAndBroadcast(payload, "pose", root -> TelemetryEventDto.of(
                "pose",
                root.get("robot_id").asText(),
                OffsetDateTime.parse(root.get("timestamp").asText()),
                Map.of("x", root.get("data").get("x").asDouble(), "y", root.get("data").get("y").asDouble())
        ));
    }

    @KafkaListener(topics = "telemetry.battery", groupId = "api-sse-broadcaster")
    public void onBattery(String payload) {
        parseAndBroadcast(payload, "battery", root -> TelemetryEventDto.of(
                "battery",
                root.get("robot_id").asText(),
                OffsetDateTime.parse(root.get("timestamp").asText()),
                Map.of("level", root.get("data").get("level").asDouble())
        ));
    }

    @KafkaListener(topics = "telemetry.status", groupId = "api-sse-broadcaster")
    public void onStatus(String payload) {
        parseAndBroadcast(payload, "status", root -> TelemetryEventDto.of(
                "status",
                root.get("robot_id").asText(),
                OffsetDateTime.parse(root.get("timestamp").asText()),
                Map.of("state", root.get("data").get("state").asText())
        ));
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
