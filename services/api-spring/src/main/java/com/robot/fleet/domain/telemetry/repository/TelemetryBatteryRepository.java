package com.robot.fleet.domain.telemetry.repository;

import com.robot.fleet.domain.telemetry.entity.TelemetryBattery;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TelemetryBatteryRepository extends JpaRepository<TelemetryBattery, Long> {
    Optional<TelemetryBattery> findTopByRobotIdOrderByOccurredAtDesc(String robotId);
    List<TelemetryBattery> findByRobotIdOrderByOccurredAtDesc(String robotId, Pageable pageable);
}
