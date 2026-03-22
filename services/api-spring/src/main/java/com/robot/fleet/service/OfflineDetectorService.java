package com.robot.fleet.service;

import com.robot.fleet.dto.TelemetryEventDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class OfflineDetectorService {

    private final TelemetryStreamService streamService;

    @Value("${fleet.offline-threshold-seconds:30}")
    private long thresholdSeconds;

    public boolean isOffline(String robotId) {
        OffsetDateTime lastSeen = streamService.getLastSeenMap().get(robotId);
        if (lastSeen == null) return true;
        return lastSeen.isBefore(OffsetDateTime.now().minusSeconds(thresholdSeconds));
    }

    @Scheduled(fixedDelayString = "${fleet.offline-check-ms:5000}")
    public void detectOfflineRobots() {
        OffsetDateTime threshold = OffsetDateTime.now().minusSeconds(thresholdSeconds);
        for (Map.Entry<String, OffsetDateTime> entry : streamService.getLastSeenMap().entrySet()) {
            String robotId = entry.getKey();
            OffsetDateTime lastSeen = entry.getValue();
            if (lastSeen.isBefore(threshold)) {
                log.info("[Offline] 로봇 오프라인 감지: {} (마지막 수신: {})", robotId, lastSeen);
                streamService.broadcast(TelemetryEventDto.of(
                        "offline",
                        robotId,
                        OffsetDateTime.now(),
                        Map.of("state", "offline")
                ));
            }
        }
    }
}
