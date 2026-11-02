package com.robot.fleet.domain.ack.controller;

import com.robot.fleet.domain.ack.dto.AckDto;
import com.robot.fleet.domain.ack.service.AckQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/robots/{robotId}/acks")
public class AckController {
    private final AckQueryService service;
    @GetMapping("/{cmdId}")
    public ResponseEntity<AckDto> find(@PathVariable String robotId, @PathVariable String cmdId) {
        return ResponseEntity.of(service.find(robotId, UUID.fromString(cmdId)));
    }
}
