package com.robot.fleet.domain.simulation.controller;

import com.robot.fleet.domain.simulation.dto.SimulationReport;
import com.robot.fleet.domain.simulation.service.SimulationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

@RestController
@RequestMapping("/internal/simulations")
public class SimulationInternalController {
    private final SimulationService service;
    private final String token;
    private final String fleetId;

    public SimulationInternalController(SimulationService service,
                                       @Value("${simulation.internal-token:}") String token,
                                       @Value("${fleet.id:default}") String fleetId) {
        this.service = service;
        this.token = token;
        this.fleetId = fleetId;
    }

    private void authenticate(String candidate) {
        if (token.isBlank()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "관리 토큰 미설정");
        if (candidate == null || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                candidate.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "관리 인증 실패");
        }
    }

    @GetMapping
    public Map<String, Object> snapshot(@RequestHeader(value = "X-Simulator-Token", required = false) String candidate) {
        authenticate(candidate);
        return Map.of("fleetId", fleetId, "simulations", service.list().stream()
                .filter(robot -> !robot.state().equals("DELETED")).toList());
    }

    @PostMapping("/reports")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void report(@RequestHeader(value = "X-Simulator-Token", required = false) String candidate,
                       @RequestBody java.util.List<SimulationReport> reports) {
        authenticate(candidate);
        if (reports.size() > 100 || reports.stream().anyMatch(java.util.Objects::isNull)) throw new IllegalArgumentException("상태 보고 상한 초과");
        reports.forEach(service::report);
    }
}
