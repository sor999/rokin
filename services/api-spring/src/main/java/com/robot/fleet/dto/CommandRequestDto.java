package com.robot.fleet.dto;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.HashMap;
import java.util.Map;

// WebSocket STOMP Command 요청 (Jackson 역직렬화를 위해 Lombok 사용)
@Getter
@Setter
@NoArgsConstructor
public class CommandRequestDto {
    private String robotId;
    private String command;
    private Map<String, Object> data = new HashMap<>();
    private String issuedBy = "dashboard";
}
