package com.robot.fleet.domain.simulation.controller;

import com.robot.fleet.domain.simulation.service.DistributionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

import static com.robot.fleet.domain.simulation.dto.DistributionDtos.*;

@RestController
@RequestMapping("/internal/distribution")
public class DistributionController {
    private final DistributionService service;
    private final String token;

    public DistributionController(
            DistributionService service,
            @Value("${simulation.internal-token:}") String token) {
        this.service = service;
        this.token = token;
    }

    private void authenticate(String candidate) {
        if (token.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "관리 토큰 미설정");
        }
        if (candidate == null || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                candidate.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "관리 인증 실패");
        }
    }

    @PostMapping("/pulse")
    public Snapshot pulse(
            @RequestHeader(value = "X-Simulator-Token", required = false) String candidate,
            @RequestBody PulseRequest request) {
        authenticate(candidate);
        return service.pulse(request);
    }

    @PostMapping("/released")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void released(
            @RequestHeader(value = "X-Simulator-Token", required = false) String candidate,
            @RequestBody ReleaseBatch request) {
        authenticate(candidate);
        service.released(request);
    }

    @PostMapping("/servers/{runtime}/drain")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void drain(
            @RequestHeader(value = "X-Simulator-Token", required = false) String candidate,
            @PathVariable String runtime) {
        authenticate(candidate);
        service.drain(runtime);
    }

    @GetMapping("/servers")
    public List<Server> servers(
            @RequestHeader(value = "X-Simulator-Token", required = false) String candidate) {
        authenticate(candidate);
        return service.servers();
    }
}
