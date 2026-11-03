package com.robot.fleet.domain.presence;

import com.robot.fleet.domain.presence.repository.PresenceRepository;
import com.robot.fleet.domain.presence.service.PresenceService;
import com.robot.fleet.domain.telemetry.service.OfflineDetectorService;
import com.robot.fleet.domain.telemetry.service.TelemetryStreamService;
import org.junit.jupiter.api.*;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PresenceServiceTest {
    @Test @DisplayName("공유 저장소 장애 시 로컬 온라인 상태로 대체하지 않고 503을 반환한다")
    void online_FailsClosedAndRecovers() {
        // given
        var repository = mock(PresenceRepository.class);
        var service = new PresenceService(repository, "default", 30);
        when(repository.online("default", "robot", "runtime", 30))
                .thenThrow(new DataAccessResourceFailureException("offline")).thenReturn(true);
        // when, then
        assertThatThrownBy(() -> service.online("robot", "runtime"))
                .isInstanceOfSatisfying(ResponseStatusException.class, error -> assertThat(error.getStatusCode().value()).isEqualTo(503));
        assertThat(service.online("robot", "runtime")).isTrue();
    }

    @Test @DisplayName("온라인 상태를 조회하지 못하면 화면 연결을 닫아 재조회를 유도한다")
    void detectOffline_ClosesStreamsWhenStoreUnavailable() {
        // given
        var presence = mock(PresenceService.class);
        var stream = mock(TelemetryStreamService.class);
        when(presence.expired()).thenThrow(new ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE));
        var detector = new OfflineDetectorService(presence, stream);
        // when
        detector.detectOfflineRobots();
        // then
        verify(stream).reset();
    }
    @Test @DisplayName("오프라인 이벤트는 조회한 관측 세대의 시각을 유지한다")
    void detectOffline_UsesExpiredSourceTimestamp() {
        // given
        var presence = mock(PresenceService.class);
        var stream = mock(TelemetryStreamService.class);
        var observedAt = java.time.OffsetDateTime.now().minusSeconds(31);
        when(presence.expired()).thenReturn(java.util.List.of(new PresenceRepository.Expired("robot", "runtime", observedAt)));
        // when
        new OfflineDetectorService(presence, stream).detectOfflineRobots();
        // then
        verify(stream).broadcast(new com.robot.fleet.domain.telemetry.dto.OfflineTelemetryDto("offline", "robot", observedAt,
                "runtime", new com.robot.fleet.domain.telemetry.dto.StatusTelemetryDataDto("offline")));
    }

}
