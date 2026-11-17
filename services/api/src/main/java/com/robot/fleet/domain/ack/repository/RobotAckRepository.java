package com.robot.fleet.domain.ack.repository;

import com.robot.fleet.domain.ack.entity.RobotAck;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RobotAckRepository extends JpaRepository<RobotAck, Long> {
    Optional<RobotAck> findTopByRobotIdAndCmdIdOrderByIdDesc(String robotId, UUID cmdId);
    List<RobotAck> findByCmdIdOrderByOccurredAtDesc(UUID cmdId);
}
