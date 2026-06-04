package com.robot.fleet.domain.telemetry.entity;

import com.robot.fleet.global.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "telemetry_battery")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TelemetryBattery extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", unique = true, nullable = false)
    private UUID eventId;

    @Column(name = "timestamp", nullable = false)
    private OffsetDateTime occurredAt;

    @Column(name = "robot_id", nullable = false)
    private String robotId;

    private Float level;

}
