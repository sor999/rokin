package com.robot.fleet.domain;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Entity
@Table(name = "telemetry_pose")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TelemetryPose extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", unique = true, nullable = false)
    private String eventId;

    @Column(name = "timestamp", nullable = false)
    private OffsetDateTime occurredAt;

    @Column(name = "robot_id", nullable = false)
    private String robotId;

    private Double x;
    private Double y;

}
