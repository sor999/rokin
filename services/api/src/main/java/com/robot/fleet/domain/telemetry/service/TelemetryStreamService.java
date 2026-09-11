package com.robot.fleet.domain.telemetry.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.robot.fleet.domain.telemetry.dto.TelemetryEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
@Service
@RequiredArgsConstructor
public class TelemetryStreamService {

    private final ObjectMapper objectMapper;

    private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();

    // 로봇별 마지막 수신 시각 (Offline 탐지용)
    private final Map<String, OffsetDateTime> lastSeenMap = new ConcurrentHashMap<>();

    public Map<String, OffsetDateTime> getLastSeenMap() {
        return lastSeenMap;
    }

    public SseEmitter createEmitter() {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        log.info("[SSE] 클라이언트 연결 | 현재 구독자: {}", emitters.size());
        return emitter;
    }

    public void broadcast(TelemetryEvent event) {
        lastSeenMap.put(event.robotId(), event.occurredAt());
        String data;
        try {
            data = objectMapper.writeValueAsString(event);
        } catch (Exception e) {
            log.warn("[SSE] broadcast 직렬화 실패: {}", e.getMessage());
            return; // 직렬화 실패 시 예외 전파 없이 해당 브로드캐스트만 중단
        }
        
        List<SseEmitter> dead = new ArrayList<>();
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().name("robot_update").data(data));
            } catch (Exception e) {
                dead.add(emitter);
            }
        }
        emitters.removeAll(dead);
    }

    @Scheduled(fixedRateString = "${fleet.sse-ping-ms:15000}")
    public void sendPing() {
        if (emitters.isEmpty()) return;
        List<SseEmitter> dead = new ArrayList<>();
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event().comment("ping"));
            } catch (Exception e) {
                dead.add(emitter);
            }
        }
        emitters.removeAll(dead);
    }
}
