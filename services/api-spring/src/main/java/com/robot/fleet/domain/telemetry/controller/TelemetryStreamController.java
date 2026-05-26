package com.robot.fleet.domain.telemetry.controller;

import com.robot.fleet.domain.telemetry.service.TelemetryStreamService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@CrossOrigin(originPatterns = "http://localhost:*")
@RestController
@RequestMapping("/api/stream")
@RequiredArgsConstructor
public class TelemetryStreamController {

    private final TelemetryStreamService streamService;

    // 클라이언트가 구독 시작: GET /api/stream/telemetry
    // 이후 robot_update SSE 이벤트로 실시간 텔레메트리 수신
    @GetMapping(value = "/telemetry", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamTelemetry() {
        return streamService.createEmitter();
    }
}
