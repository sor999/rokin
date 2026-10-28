package com.robot.fleet.domain.command.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.robot.fleet.domain.command.dto.CommandRequestDto;
import org.springframework.beans.factory.annotation.Value;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
public class CommandService {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    private final String fleetId;

    public CommandService(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper,
                          @Value("${fleet.id:default}") String fleetId) {
        if (fleetId == null || !fleetId.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("Invalid fleet.id");
        }
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
        this.fleetId = fleetId;
    }

    public String sendCommand(CommandRequestDto request) {
        String cmdId = UUID.randomUUID().toString();
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("cmd_id", cmdId);
            payload.put("fleet_id", fleetId);
            payload.put("timestamp", OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
            payload.put("robot_id", request.getRobotId());
            payload.put("command", request.getCommand());
            payload.put("data", request.getData());
            payload.put("issued_by", request.getIssuedBy());

            String json = objectMapper.writeValueAsString(payload);
            log.info("[Command] Kafka 발행 요청 | robotId={} cmdId={} payload={}", request.getRobotId(), cmdId, json);
            kafkaTemplate.send("cmd.robot", request.getRobotId(), json)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("[Command] Kafka 발행 실패 | robotId={} cmdId={} error={}", request.getRobotId(), cmdId, ex.getMessage());
                    } else {
                        log.info("[Command] Kafka 발행 성공 | robotId={} cmdId={} command={}", request.getRobotId(), cmdId, request.getCommand());
                    }
                });
        } catch (Exception e) {
            throw new IllegalStateException("명령 직렬화 실패", e);
        }
        return cmdId;
    }
}
