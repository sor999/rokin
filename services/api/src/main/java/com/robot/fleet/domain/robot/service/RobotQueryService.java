package com.robot.fleet.domain.robot.service;

import com.robot.fleet.domain.telemetry.entity.TelemetryBattery;
import com.robot.fleet.domain.telemetry.entity.TelemetryPose;
import com.robot.fleet.domain.telemetry.entity.TelemetryStatus;
import com.robot.fleet.domain.telemetry.dto.BatteryDto;
import com.robot.fleet.domain.telemetry.dto.PoseDto;
import com.robot.fleet.domain.robot.dto.RobotStatusDto;
import com.robot.fleet.domain.telemetry.repository.TelemetryBatteryRepository;
import com.robot.fleet.domain.telemetry.repository.TelemetryPoseRepository;
import com.robot.fleet.domain.telemetry.repository.TelemetryStatusRepository;
import com.robot.fleet.domain.telemetry.service.OfflineDetectorService;
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
    private final com.robot.fleet.domain.simulation.service.SimulationService simulations;

    public List<RobotStatusDto> getAllRobots() {
        var registered = simulations.list();
        var removed = registered.stream().filter(r -> r.desiredState().equals("DELETED"))
                .map(com.robot.fleet.domain.simulation.dto.SimulationDto::robotId).collect(java.util.stream.Collectors.toSet());
        return java.util.stream.Stream.concat(poseRepo.findDistinctRobotIds().stream(),
                        registered.stream().filter(r -> r.desiredState().equals("RUNNING"))
                                .map(com.robot.fleet.domain.simulation.dto.SimulationDto::robotId))
                .distinct().filter(id -> !removed.contains(id))
                .map(robotId -> {
                    String runtimeId = registered.stream().filter(r -> r.robotId().equals(robotId))
                            .map(r -> r.runtimeId() == null ? "" : r.runtimeId()).findFirst().orElse("");
                    boolean pending = runtimeId.isEmpty() && registered.stream().anyMatch(r -> r.robotId().equals(robotId));
                    TelemetryPose pose = pending ? null : (runtimeId.isEmpty() ? poseRepo.findTopByRobotIdOrderByOccurredAtDesc(robotId)
                            : poseRepo.findTopByRobotIdAndSimulationRuntimeIdOrderByOccurredAtDesc(robotId, runtimeId)).orElse(null);
                    TelemetryBattery battery = pending ? null : (runtimeId.isEmpty() ? batteryRepo.findTopByRobotIdOrderByOccurredAtDesc(robotId)
                            : batteryRepo.findTopByRobotIdAndSimulationRuntimeIdOrderByOccurredAtDesc(robotId, runtimeId)).orElse(null);
                    TelemetryStatus status = pending ? null : (runtimeId.isEmpty() ? statusRepo.findTopByRobotIdOrderByOccurredAtDesc(robotId)
                            : statusRepo.findTopByRobotIdAndSimulationRuntimeIdOrderByOccurredAtDesc(robotId, runtimeId)).orElse(null);
                    return new RobotStatusDto(
                            robotId,
                            !pending && !offlineDetector.isOffline(robotId, runtimeId.isEmpty() ? null : runtimeId),
                            pose != null ? pose.getOccurredAt() : null,
                            pose != null ? PoseDto.of(pose.getX(), pose.getY(), pose.getOccurredAt()) : null,
                            battery != null ? BatteryDto.of(battery.getLevel(), battery.getOccurredAt()) : null,
                            status != null ? status.getState() : null,
                            runtimeId.isEmpty() ? null : runtimeId
                    );
                })
                .toList();
    }

    public PoseDto getLatestPose(String robotId) {
        return poseRepo.findTopByRobotIdOrderByOccurredAtDesc(robotId)
                .map(p -> PoseDto.of(p.getX(), p.getY(), p.getOccurredAt()))
                .orElse(null);
    }

    public List<PoseDto> getPoseHistory(String robotId, int limit, String runtimeId) {
        return (runtimeId == null ? poseRepo.findByRobotIdOrderByOccurredAtDesc(robotId, PageRequest.of(0, limit))
                : poseRepo.findByRobotIdAndSimulationRuntimeIdOrderByOccurredAtDesc(robotId, runtimeId, PageRequest.of(0, limit)))
                .stream()
                .map(p -> PoseDto.of(p.getX(), p.getY(), p.getOccurredAt()))
                .toList();
    }

    public BatteryDto getLatestBattery(String robotId) {
        return batteryRepo.findTopByRobotIdOrderByOccurredAtDesc(robotId)
                .map(b -> BatteryDto.of(b.getLevel(), b.getOccurredAt()))
                .orElse(null);
    }

    public List<BatteryDto> getBatteryHistory(String robotId, int limit, String runtimeId) {
        return (runtimeId == null ? batteryRepo.findByRobotIdOrderByOccurredAtDesc(robotId, PageRequest.of(0, limit))
                : batteryRepo.findByRobotIdAndSimulationRuntimeIdOrderByOccurredAtDesc(robotId, runtimeId, PageRequest.of(0, limit)))
                .stream()
                .map(b -> BatteryDto.of(b.getLevel(), b.getOccurredAt()))
                .toList();
    }
}
