package com.robot.fleet.domain.telemetry.repository;

import com.robot.fleet.domain.telemetry.entity.TelemetryStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TelemetryStatusRepository extends JpaRepository<TelemetryStatus, Long> {
    Optional<TelemetryStatus> findTopByRobotIdOrderByOccurredAtDesc(String robotId);
}
