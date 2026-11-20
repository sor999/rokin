package com.robot.fleet.domain.telemetry.service;

import com.robot.fleet.domain.presence.service.PresenceService;
import com.robot.fleet.domain.telemetry.dto.OfflineTelemetryDto;
import com.robot.fleet.domain.telemetry.dto.StatusTelemetryDataDto;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class OfflineDetectorService {
    private final PresenceService presence;
    private final TelemetryStreamService streamService;

    public boolean isOffline(String robotId, String runtimeId) {
        return !presence.online(robotId, runtimeId);
    }

    @Scheduled(fixedRateString = "${fleet.offline-check-ms:5000}", initialDelayString = "${fleet.offline-initial-delay:1000}")
    public void detectOfflineRobots() {
        try {
            for (var robot : presence.expired()) {
                streamService.broadcast(new OfflineTelemetryDto("offline", robot.robotId(), robot.observedAt(),
                        robot.runtimeId(), new StatusTelemetryDataDto("offline")));
            }
        } catch (ResponseStatusException
                 | DataAccessException
                 | TransactionException unavailable) {
            streamService.reset();
        }
    }
}
