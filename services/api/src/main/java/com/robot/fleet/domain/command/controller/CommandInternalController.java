package com.robot.fleet.domain.command.controller;

import com.robot.fleet.domain.command.service.DurableCommandService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

@RestController
@RequestMapping("/internal/commands")
public class CommandInternalController {
    private final DurableCommandService service;
    private final String token;
    public CommandInternalController(DurableCommandService service,@Value("${simulation.internal-token:}") String token) { this.service=service; this.token=token; }
    private void authenticate(String candidate) {
        if (token.isBlank()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"관리 토큰 미설정");
        if (candidate==null || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),candidate.getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"관리 인증 실패");
    }
    @PostMapping("/received") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void received(@RequestHeader(value="X-Simulator-Token",required=false) String candidate,@RequestBody List<DurableCommandService.Receipt> entries) {
        authenticate(candidate); service.received(entries);
    }
    @PostMapping("/ack-receipts")
    public Map<String,List<UUID>> confirmed(@RequestHeader(value="X-Simulator-Token",required=false) String candidate,@RequestBody List<UUID> events) {
        authenticate(candidate); return Map.of("eventIds",service.confirmed(events));
    }
}
