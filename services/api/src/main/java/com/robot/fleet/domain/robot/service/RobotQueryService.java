package com.robot.fleet.domain.robot.service;

import com.robot.fleet.domain.robot.dto.RobotStatusDto;
import com.robot.fleet.domain.robot.repository.RobotSnapshotRepository;
import com.robot.fleet.domain.telemetry.dto.BatteryDto;
import com.robot.fleet.domain.telemetry.dto.PoseDto;
import com.robot.fleet.domain.telemetry.repository.TelemetryBatteryRepository;
import com.robot.fleet.domain.telemetry.repository.TelemetryPoseRepository;
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
    private final RobotSnapshotRepository snapshots;

    public List<RobotStatusDto> getAllRobots() { return getRobots("", null); }

    public List<RobotStatusDto> getRobots(String after, Integer limit) {
        if (after == null || (!after.isEmpty() && !after.matches("[A-Za-z][A-Za-z0-9_-]{0,127}"))
                || (limit != null && (limit < 1 || limit > 1000))) throw new IllegalArgumentException("잘못된 페이지 요청입니다.");
        return snapshots.list(after, limit);
    }

    public PoseDto getLatestPose(String robotId) { return snapshots.find(robotId).map(RobotStatusDto::pose).orElse(null); }

    public List<PoseDto> getPoseHistory(String robotId, int limit, String runtimeId) {
        return (runtimeId == null ? poseRepo.findByRobotIdOrderByOccurredAtDesc(robotId, PageRequest.of(0, limit))
                : poseRepo.findByRobotIdAndSimulationRuntimeIdOrderByOccurredAtDesc(robotId, runtimeId, PageRequest.of(0, limit)))
                .stream()
                .map(p -> PoseDto.of(p.getX(), p.getY(), p.getOccurredAt()))
                .toList();
    }

    public BatteryDto getLatestBattery(String robotId) { return snapshots.find(robotId).map(RobotStatusDto::battery).orElse(null); }

    public List<BatteryDto> getBatteryHistory(String robotId, int limit, String runtimeId) {
        return (runtimeId == null ? batteryRepo.findByRobotIdOrderByOccurredAtDesc(robotId, PageRequest.of(0, limit))
                : batteryRepo.findByRobotIdAndSimulationRuntimeIdOrderByOccurredAtDesc(robotId, runtimeId, PageRequest.of(0, limit)))
                .stream()
                .map(b -> BatteryDto.of(b.getLevel(), b.getOccurredAt()))
                .toList();
    }
}
