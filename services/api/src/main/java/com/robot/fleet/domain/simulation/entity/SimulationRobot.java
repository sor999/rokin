package com.robot.fleet.domain.simulation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.time.OffsetDateTime;

@Entity
@Table(name = "simulation_robot")
@Immutable
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class SimulationRobot {
    @Id
    @Column(name = "robot_id", length = 64)
    private String robotId;
    @Column(name = "fleet_id", nullable = false, length = 128)
    private String fleetId;
    @Column(name = "start_x", nullable = false)
    private double startX;
    @Column(name = "start_y", nullable = false)
    private double startY;
    @Column(nullable = false)
    private double speed;
    @Column(name = "desired_state", nullable = false, length = 16)
    private String desiredState;
    @Column(name = "config_version", nullable = false)
    private long version;
    @Column(name = "runtime_state", nullable = false, length = 16)
    private String state;
    @Column(length = 500)
    private String error;
    @Column(name = "runtime_id", length = 36)
    private String runtimeId;
    @Column(name = "reported_at")
    private OffsetDateTime reportedAt;
}
