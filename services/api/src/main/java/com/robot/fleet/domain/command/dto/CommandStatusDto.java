package com.robot.fleet.domain.command.dto;

import com.robot.fleet.domain.ack.dto.AckDto;
import java.time.OffsetDateTime;

public record CommandStatusDto(String cmdId, String robotId, String deliveryStatus,
                               OffsetDateTime expiresAt, AckDto ack) { }
