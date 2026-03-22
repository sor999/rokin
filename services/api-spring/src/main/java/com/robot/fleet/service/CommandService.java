package com.robot.fleet.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.robot.fleet.dto.CommandRequestDto;
import lombok.RequiredArgsConstructor;
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
@RequiredArgsConstructor
public class CommandService {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public String sendCommand(CommandRequestDto request) {
        String cmdId = UUID.randomUUID().toString();
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("cmd_id", cmdId);
            payload.put("timestamp", OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
            payload.put("robot_id", request.getRobotId());
            payload.put("command", request.getCommand());
            payload.put("data", request.getData());
            payload.put("issued_by", request.getIssuedBy());

            String json = objectMapper.writeValueAsString(payload);
            kafkaTemplate.send("cmd.robot", request.getRobotId(), json);
            log.info("[Command] Kafka 발행 | robotId={} cmdId={} command={}", request.getRobotId(), cmdId, request.getCommand());
        } catch (Exception e) {
            throw new IllegalStateException("명령 직렬화 실패", e);
        }
        return cmdId;
    }
}
