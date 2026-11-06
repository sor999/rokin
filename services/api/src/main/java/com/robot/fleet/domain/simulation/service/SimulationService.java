package com.robot.fleet.domain.simulation.service;

import com.robot.fleet.domain.simulation.dto.*;
import com.robot.fleet.domain.simulation.repository.SimulationRepository;
import com.robot.fleet.domain.telemetry.service.OfflineDetectorService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

@Service
@Transactional
public class SimulationService implements ApplicationRunner {
    private final SimulationRepository repository;
    private final OfflineDetectorService offlineDetector;
    private final String fleetId;
    private final int maxRobots;

    public SimulationService(SimulationRepository repository, OfflineDetectorService offlineDetector,
                             @Value("${fleet.id:default}") String fleetId,
                             @Value("${simulation.max-robots:20}") int maxRobots) {
        if (maxRobots < 3) throw new IllegalArgumentException("simulation.max-robots must be at least 3");
        this.repository = repository;
        this.offlineDetector = offlineDetector;
        this.fleetId = fleetId;
        this.maxRobots = maxRobots;
    }

    @Override
    public void run(ApplicationArguments args) {
        repository.lockFleet(fleetId);
        for (int i = 1; i <= 3; i++) {
            String id = "robot_" + i;
            if (repository.findOwner(id).isEmpty()) {
                repository.insert(fleetId, id, i == 2 ? 5 : i == 3 ? -5 : 0,
                        i == 2 ? 5 : i == 3 ? 2 : 0, i == 1 ? 1.5 : i == 2 ? 1 : 2, true);
            }
        }
    }

    public SimulationDto create(CreateSimulationRequest request) {
        request.validate();
        repository.lockFleet(fleetId);
        if (repository.findOwner(request.robotId()).isPresent() || repository.hasTelemetry(request.robotId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 사용한 로봇 ID입니다.");
        }
        if (repository.countActive(fleetId) >= maxRobots) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "동시 실행 상한에 도달했습니다.");
        }
        try { repository.insert(fleetId, request.robotId(), request.startX(), request.startY(), request.speed()); }
        catch (DuplicateKeyException conflict) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 사용한 로봇 ID입니다.");
        }
        return get(request.robotId());
    }

    public SimulationDto delete(String robotId) {
        SimulationDto robot = get(robotId);
        if (!robot.desiredState().equals("DELETED")) repository.delete(robotId);
        return get(robotId);
    }

    @Transactional(readOnly = true)
    public List<SimulationDto> list() { return list(false); }

    @Transactional(readOnly = true)
    public List<SimulationDto> list(boolean activeOnly) {
        String current = repository.currentRuntime(fleetId).orElse(null);
        OffsetDateTime threshold = repository.now().minusSeconds(10);
        return repository.findAll(fleetId, activeOnly).stream().map(robot -> {
            if (robot.desiredState().equals("RUNNING") && current != null && !current.equals(robot.runtimeId())) {
                return new SimulationDto(robot.robotId(), robot.fleetId(), robot.startX(), robot.startY(), robot.speed(),
                        robot.desiredState(), robot.version(), "STARTING", "새 실행 인스턴스를 준비 중입니다.", current, null);
            }
            if (robot.state().equals("RUNNING") && (robot.reportedAt() == null || robot.reportedAt().isBefore(threshold))) {
                return robot.withState("STARTING", "시뮬레이터 연결을 기다리는 중입니다.");
            }
            return robot;
        }).toList();
    }

    private SimulationDto get(String robotId) {
        return repository.find(fleetId, robotId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "로봇을 찾을 수 없습니다."));
    }

    public void report(SimulationReport report) {
        if (report.version() < 1 || report.robotId() == null || report.runtimeId() == null
                || !report.runtimeId().matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}") || report.state() == null
                || !List.of("STARTING", "RUNNING", "FAILED", "DELETED").contains(report.state())
                || (report.error() != null && report.error().length() > 500)) {
            throw new IllegalArgumentException("잘못된 실행 상태 보고입니다.");
        }
        if (!repository.lockRuntimeForReport(fleetId)) return;
        repository.report(fleetId, report);
    }

    public void registerRuntime(String runtimeId) {
        if (runtimeId == null || !runtimeId.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) {
            throw new IllegalArgumentException("잘못된 실행 인스턴스 ID입니다.");
        }
        repository.lockFleet(fleetId);
        if (repository.currentRuntime(fleetId).filter(runtimeId::equals).isPresent()) return;
        if (repository.runtimeUsed(runtimeId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "종료된 실행 인스턴스입니다.");
        }
        try { repository.registerRuntime(fleetId, runtimeId); }
        catch (DuplicateKeyException conflict) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "종료된 실행 인스턴스입니다.");
        }
    }

    @Transactional(readOnly = true, timeout = 1)
    public boolean acceptsTelemetry(String robotId, String runtimeId) {
        return repository.acceptsTelemetry(fleetId, robotId, runtimeId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> commandContext(String robotId) {
        SimulationDto robot = repository.find(fleetId, robotId).orElse(null);
        if (robot == null) {
            if (repository.findOwner(robotId).isPresent()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "다른 Fleet의 로봇입니다.");
            }
            if (offlineDetector.isOffline(robotId, null)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "온라인 상태를 확인하세요.");
            }
            return Map.of();
        }
        if (!robot.desiredState().equals("RUNNING") || !robot.state().equals("RUNNING")
                || robot.reportedAt() == null || robot.reportedAt().isBefore(repository.now().minusSeconds(10))
                || offlineDetector.isOffline(robotId, robot.runtimeId())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "실행 준비 및 온라인 상태를 확인하세요.");
        }
        return Map.of("simulation_version", robot.version(), "simulation_runtime_id", robot.runtimeId());
    }
}
