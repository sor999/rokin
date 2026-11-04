package com.robot.fleet.domain.command.controller;

import com.robot.fleet.domain.command.service.DurableCommandService;
import com.robot.fleet.domain.command.dto.CommandStatusDto;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/commands")
public class CommandQueryController {
    private final DurableCommandService service;
    public CommandQueryController(DurableCommandService service) { this.service=service; }
    @GetMapping("/{cmdId}")
    public ResponseEntity<CommandStatusDto> find(@PathVariable UUID cmdId) { return ResponseEntity.of(service.find(cmdId)); }
    @GetMapping
    public ResponseEntity<CommandStatusDto> byKey(@RequestParam UUID idempotencyKey) { return ResponseEntity.of(service.byKey(idempotencyKey)); }
}
