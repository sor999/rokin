package com.robot.fleet.domain.command.dto;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

// WebSocket STOMP Command 요청 (Jackson 역직렬화를 위해 Lombok 사용)
@Getter
@Setter
@NoArgsConstructor
public class CommandRequestDto {
    private String robotId;
    private String command;
    @JsonTypeInfo(
            use = JsonTypeInfo.Id.NAME,
            include = JsonTypeInfo.As.EXTERNAL_PROPERTY,
            property = "command"
    )
    @JsonSubTypes({
            @JsonSubTypes.Type(value = MoveToCommandDataDto.class, name = "move_to"),
            @JsonSubTypes.Type(value = StopCommandDataDto.class, name = "stop")
    })
    private CommandData data;
    private String issuedBy = "dashboard";
}
