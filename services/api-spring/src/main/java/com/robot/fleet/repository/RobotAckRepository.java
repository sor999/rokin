package com.robot.fleet.repository;

import com.robot.fleet.domain.RobotAck;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RobotAckRepository extends JpaRepository<RobotAck, Long> {
    List<RobotAck> findByCmdIdOrderByOccurredAtDesc(String cmdId);
}
