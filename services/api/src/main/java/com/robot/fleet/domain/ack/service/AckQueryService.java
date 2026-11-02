package com.robot.fleet.domain.ack.service;

import com.robot.fleet.domain.ack.dto.AckDto;
import com.robot.fleet.domain.ack.repository.RobotAckRepository;
import com.robot.fleet.domain.simulation.repository.SimulationRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
public class AckQueryService {
    private final RobotAckRepository repository;
    private final SimulationRepository simulations;
    private final String fleetId;

    public AckQueryService(RobotAckRepository repository, SimulationRepository simulations,
                           @Value("${fleet.id:default}") String fleetId) {
        this.repository = repository; this.simulations = simulations; this.fleetId = fleetId;
    }

    @Transactional(readOnly = true)
    public Optional<AckDto> find(String robotId, UUID cmdId) {
        if (simulations.findOwner(robotId).filter(owner -> !fleetId.equals(owner)).isPresent()) return Optional.empty();
        return repository.findTopByRobotIdAndCmdIdOrderByIdDesc(robotId, cmdId).map(ack -> AckDto.of(
                ack.getCmdId().toString(), ack.getRobotId(), ack.getStatus(), ack.getMessage(), ack.getOccurredAt()));
    }
}
