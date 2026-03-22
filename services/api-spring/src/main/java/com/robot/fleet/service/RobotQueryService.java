package com.robot.fleet.service;

import com.robot.fleet.domain.TelemetryBattery;
import com.robot.fleet.domain.TelemetryPose;
import com.robot.fleet.domain.TelemetryStatus;
import com.robot.fleet.dto.BatteryDto;
import com.robot.fleet.dto.PoseDto;
import com.robot.fleet.dto.RobotStatusDto;
import com.robot.fleet.repository.TelemetryBatteryRepository;
import com.robot.fleet.repository.TelemetryPoseRepository;
import com.robot.fleet.repository.TelemetryStatusRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class RobotQueryService {

    private final TelemetryPoseRepository poseRepo;
    private final TelemetryBatteryRepository batteryRepo;
    private final TelemetryStatusRepository statusRepo;
    private final OfflineDetectorService offlineDetector;

    public List<RobotStatusDto> getAllRobots() {
        return poseRepo.findDistinctRobotIds().stream()
                .map(robotId -> {
                    TelemetryPose pose = poseRepo.findTopByRobotIdOrderByOccurredAtDesc(robotId).orElse(null);
                    TelemetryBattery battery = batteryRepo.findTopByRobotIdOrderByOccurredAtDesc(robotId).orElse(null);
                    TelemetryStatus status = statusRepo.findTopByRobotIdOrderByOccurredAtDesc(robotId).orElse(null);
                    return RobotStatusDto.of(
                            robotId,
                            !offlineDetector.isOffline(robotId),
                            pose != null ? pose.getOccurredAt() : null,
                            pose != null ? PoseDto.of(pose.getX(), pose.getY(), pose.getOccurredAt()) : null,
                            battery != null ? BatteryDto.of(battery.getLevel(), battery.getOccurredAt()) : null,
                            status != null ? status.getState() : null
                    );
                })
                .toList();
    }

    public PoseDto getLatestPose(String robotId) {
        return poseRepo.findTopByRobotIdOrderByOccurredAtDesc(robotId)
                .map(p -> PoseDto.of(p.getX(), p.getY(), p.getOccurredAt()))
                .orElse(null);
    }

    public List<PoseDto> getPoseHistory(String robotId, int limit) {
        return poseRepo.findByRobotIdOrderByOccurredAtDesc(robotId, PageRequest.of(0, limit))
                .stream()
                .map(p -> PoseDto.of(p.getX(), p.getY(), p.getOccurredAt()))
                .toList();
    }

    public BatteryDto getLatestBattery(String robotId) {
        return batteryRepo.findTopByRobotIdOrderByOccurredAtDesc(robotId)
                .map(b -> BatteryDto.of(b.getLevel(), b.getOccurredAt()))
                .orElse(null);
    }

    public List<BatteryDto> getBatteryHistory(String robotId, int limit) {
        return batteryRepo.findByRobotIdOrderByOccurredAtDesc(robotId, PageRequest.of(0, limit))
                .stream()
                .map(b -> BatteryDto.of(b.getLevel(), b.getOccurredAt()))
                .toList();
    }
}
