package com.robot.fleet.domain.telemetry.repository;

import com.robot.fleet.domain.telemetry.entity.TelemetryPose;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface TelemetryPoseRepository extends JpaRepository<TelemetryPose, Long> {
    Optional<TelemetryPose> findTopByRobotIdOrderByOccurredAtDesc(String robotId);
    List<TelemetryPose> findByRobotIdOrderByOccurredAtDesc(String robotId, Pageable pageable);

    @Query("SELECT DISTINCT p.robotId FROM TelemetryPose p")
    List<String> findDistinctRobotIds();
}
