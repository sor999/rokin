package com.robot.fleet.domain.simulation.service;

import com.robot.fleet.domain.simulation.repository.SimulationRepository;
import com.robot.fleet.domain.simulation.repository.DistributionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Comparator;
import java.util.List;

import static com.robot.fleet.domain.simulation.dto.DistributionDtos.*;

@Service
@Transactional(timeout = 5)
public class DistributionService {
    private final DistributionRepository repository;
    private final SimulationRepository simulations;
    private final String fleet;
    private final boolean enabled;
    private final long ttl;

    public DistributionService(
            DistributionRepository repository,
            SimulationRepository simulations,
            @Value("${fleet.id:default}") String fleet,
            @Value("${simulation.distributed-enabled:false}") boolean enabled,
            @Value("${simulation.lease-millis:12000}") long ttl) {
        if (ttl < 6000 || ttl > 30000) {
            throw new IllegalArgumentException("Invalid distribution lease duration");
        }
        this.repository = repository;
        this.simulations = simulations;
        this.fleet = fleet;
        this.enabled = enabled;
        this.ttl = ttl;
    }

    private void requireEnabled() {
        if (!enabled) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "분산 실행이 비활성화됐습니다.");
        }
    }

    private void validateRuntime(String runtime) {
        if (runtime == null || !runtime.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")) {
            throw new IllegalArgumentException("Invalid execution server ID");
        }
    }

    public Snapshot pulse(PulseRequest request) {
        requireEnabled();
        validateRuntime(request.runtimeId());
        if (request.capacity() < 1 || request.capacity() > 10000) {
            throw new IllegalArgumentException("Invalid execution server capacity");
        }
        simulations.lockFleet(fleet);
        repository.expire(fleet);
        var server = repository.server(fleet, request.runtimeId()).orElse(null);
        if (server == null) {
            if (repository.runtimeUsed(request.runtimeId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 사용한 실행 서버 ID입니다.");
            }
            if (repository.legacyRunning(fleet)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "기존 실행 권한을 먼저 종료하세요.");
            }
            repository.activate(fleet);
            repository.register(fleet, request.runtimeId(), request.capacity(), ttl);
        } else {
            if (request.capacity() < server.assignedCount()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "배정 수보다 수용 한도를 낮출 수 없습니다.");
            }
            if (!repository.renew(fleet, request.runtimeId(), request.capacity(), ttl)) {
                return new Snapshot(fleet, request.runtimeId(), "RETIRED", 0, List.of());
            }
        }
        reconcile();
        server = repository.server(fleet, request.runtimeId()).orElseThrow();
        return new Snapshot(fleet, request.runtimeId(), server.state(), ttl,
                repository.assignments(fleet, request.runtimeId()));
    }

    private Server leastLoaded() {
        return repository.servers(fleet).stream()
                .filter(server -> server.online() && server.state().equals("ACTIVE"))
                .filter(server -> server.assignedCount() < server.capacity())
                .min(Comparator.comparingDouble(server -> (double) server.assignedCount() / server.capacity()))
                .orElse(null);
    }

    private void reconcile() {
        repository.initializeAssignments(fleet);
        repository.releaseDeleted(fleet);
        for (String robot : repository.unassigned(fleet)) {
            var target = leastLoaded();
            if (target == null) break;
            repository.assign(fleet, robot, target.runtimeId(), ttl);
        }
        if (repository.releaseInProgress(fleet)) return;

        for (var server : repository.servers(fleet)) {
            if (!server.online() || server.state().equals("RETIRED")) continue;
            var target = leastLoaded();
            if (target == null || target.runtimeId().equals(server.runtimeId())) continue;
            boolean draining = server.state().equals("DRAINING");
            boolean unbalanced = server.assignedCount() > 1
                    && (double) (server.assignedCount() - 1) / server.capacity()
                    > (double) target.assignedCount() / target.capacity();
            if (!draining && !unbalanced) continue;

            var idle = repository.idleAssignments(fleet, server.runtimeId());
            if (!idle.isEmpty()) {
                repository.requestRelease(fleet, idle.getFirst());
                // 한 번에 한 로봇만 이전해 아직 반영되지 않은 목적지 부하를 과소평가하지 않는다.
                break;
            }
        }
    }

    public void released(ReleaseBatch request) {
        requireEnabled();
        validateRuntime(request.runtimeId());
        if (request.releases() == null || request.releases().size() > 100
                || request.releases().stream().anyMatch(release -> release == null
                || release.robotId() == null || release.executionId() == null || release.assignmentVersion() < 1)) {
            throw new IllegalArgumentException("Invalid release batch");
        }
        simulations.lockFleet(fleet);
        for (var release : request.releases()) {
            repository.released(fleet, request.runtimeId(), release);
        }
        repository.expire(fleet);
        reconcile();
    }

    public void drain(String runtime) {
        requireEnabled();
        validateRuntime(runtime);
        simulations.lockFleet(fleet);
        var server = repository.server(fleet, runtime).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "실행 서버를 찾을 수 없습니다."));
        if (server.state().equals("DRAINING")) return;
        if (!repository.drain(fleet, runtime)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "종료된 실행 서버입니다.");
        }
        reconcile();
    }

    @Transactional(readOnly = true)
    public List<Server> servers() {
        requireEnabled();
        return repository.servers(fleet);
    }
}
