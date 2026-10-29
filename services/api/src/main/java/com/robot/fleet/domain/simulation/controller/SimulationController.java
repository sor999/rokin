package com.robot.fleet.domain.simulation.controller;

import com.robot.fleet.domain.simulation.dto.*;
import com.robot.fleet.domain.simulation.service.SimulationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/simulations")
@RequiredArgsConstructor
public class SimulationController {
    private final SimulationService service;

    @GetMapping
    public List<SimulationDto> list() { return service.list(); }

    @PostMapping
    public ResponseEntity<SimulationDto> create(@RequestBody CreateSimulationRequest request) {
        return ResponseEntity.accepted().body(service.create(request));
    }

    @DeleteMapping("/{robotId}")
    public ResponseEntity<SimulationDto> delete(@PathVariable String robotId) {
        return ResponseEntity.accepted().body(service.delete(robotId));
    }
}
