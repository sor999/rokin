package com.robot.fleet.domain.ack.entity;

import com.robot.fleet.global.entity.BaseTimeEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "robot_ack")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RobotAck extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "cmd_id", nullable = false)
    private UUID cmdId;

    @Column(name = "timestamp", nullable = false)
    private OffsetDateTime occurredAt;

    @Column(name = "robot_id", nullable = false)
    private String robotId;

    private String status;

    private String message;

}
