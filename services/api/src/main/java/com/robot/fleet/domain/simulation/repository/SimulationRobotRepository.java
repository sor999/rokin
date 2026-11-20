package com.robot.fleet.domain.simulation.repository;

import com.robot.fleet.domain.simulation.dto.SimulationDto;
import com.robot.fleet.domain.simulation.entity.SimulationRobot;
import org.springframework.data.repository.Repository;

import java.util.List;
import java.util.Optional;

public interface SimulationRobotRepository extends Repository<SimulationRobot, String> {
    List<SimulationDto> findByFleetIdOrderByRobotId(String fleetId);

    List<SimulationDto> findByFleetIdAndStateNotOrderByRobotId(String fleetId, String state);

    Optional<SimulationDto> findByFleetIdAndRobotId(String fleetId, String robotId);

    long countByFleetIdAndStateNot(String fleetId, String state);

    Optional<Owner> findOwnerByRobotId(String robotId);

    record Owner(String fleetId) { }
}
