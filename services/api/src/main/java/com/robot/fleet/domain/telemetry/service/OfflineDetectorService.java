package com.robot.fleet.domain.telemetry.service;

import com.robot.fleet.domain.presence.service.PresenceService;
import com.robot.fleet.domain.telemetry.dto.OfflineTelemetryDto;
import com.robot.fleet.domain.telemetry.dto.StatusTelemetryDataDto;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

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
                streamService.broadcast(new OfflineTelemetryDto("offline", robot.robotId(), OffsetDateTime.now(),
                        robot.runtimeId(), new StatusTelemetryDataDto("offline")));
            }
        } catch (org.springframework.web.server.ResponseStatusException
                 | org.springframework.dao.DataAccessException
                 | org.springframework.transaction.TransactionException unavailable) {
            streamService.reset();
        }
    }
}
