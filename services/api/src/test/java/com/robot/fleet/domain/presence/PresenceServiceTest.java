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
}
