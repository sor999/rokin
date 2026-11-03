package com.robot.fleet.domain.presence.service;

import com.robot.fleet.domain.presence.repository.PresenceRepository;
import com.robot.fleet.domain.telemetry.dto.TelemetryEvent;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class PresenceService {
    private final PresenceRepository repository;
    private final String fleetId;
    private final long ttl;

    public PresenceService(PresenceRepository repository, @Value("${fleet.id:default}") String fleetId,
                           @Value("${fleet.offline-threshold-seconds:30}") long ttl) {
        if (ttl < 1 || ttl > 3600) throw new IllegalArgumentException("Invalid presence TTL");
        this.repository = repository;
        this.fleetId = fleetId;
        this.ttl = ttl;
    }

    @Transactional(timeout = 1)
    public boolean observe(TelemetryEvent event) {
        return repository.observe(fleetId, event.robotId(), event.runtimeId(), event.occurredAt(), ttl);
    }

    @Transactional(readOnly = true, timeout = 1)
    public boolean online(String robot, String runtime) {
        try { return repository.online(fleetId, robot, runtime, ttl); }
        catch (DataAccessException error) { throw unavailable(); }
    }

    @Transactional(readOnly = true, timeout = 1)
    public List<PresenceRepository.Expired> expired() {
        try { return repository.expired(fleetId, ttl); }
        catch (DataAccessException error) { throw unavailable(); }
    }

    @Scheduled(fixedDelay = 3600000)
    @Transactional(timeout = 1)
    public void prune() { repository.prune(); }

    private ResponseStatusException unavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "온라인 상태 저장소에 연결할 수 없습니다.");
    }
}
