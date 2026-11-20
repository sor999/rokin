package com.robot.fleet.domain.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.robot.fleet.domain.ack.dto.AckDto;
import com.robot.fleet.domain.simulation.service.SimulationService;
import com.robot.fleet.domain.telemetry.dto.*;
import com.robot.fleet.domain.telemetry.service.TelemetryStreamService;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RealtimeReceiverTest {
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final SimulationService simulations = mock(SimulationService.class);
    private final TelemetryStreamService stream = mock(TelemetryStreamService.class);
    private final SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
    private final RealtimeState state = new RealtimeState(true);
    private final RealtimeReceiver receiver = new RealtimeReceiver("default", mapper, simulations, stream, messaging, state);
    private final PoseTelemetryDto pose = new PoseTelemetryDto("pose", "robot_1", OffsetDateTime.parse("2026-11-03T00:00:00.123456789Z"), "current", new PoseTelemetryDataDto(1, 2));

    private String telemetry(String id, String fleet, Object data) throws Exception {
        return mapper.writeValueAsString(new RealtimeReceiver.Envelope(1, fleet, "telemetry", id, mapper.valueToTree(data)));
    }

    @Test @DisplayName("서로 다른 API의 로컬 연결에 같은 이벤트를 한 번씩 전달한다")
    void receive_FansOutAndDeduplicatesPerNode() throws Exception {
        // given
        state.activate(0);
        when(simulations.acceptsTelemetry("robot_1", "current")).thenReturn(true);
        var secondStream = mock(TelemetryStreamService.class);
        var secondState = new RealtimeState(true); secondState.activate(0);
        var second = new RealtimeReceiver("default", mapper, simulations, secondStream, messaging, secondState);
        String event = telemetry(UUID.randomUUID().toString(), "default", pose);
        // when
        receiver.receive(event, 0); receiver.receive(event, 0); second.receive(event, 0);
        // then
        verify(stream, times(1)).broadcast(pose);
        verify(secondStream, times(1)).broadcast(pose);
    }

    @Test @DisplayName("retired runtime와 과거 연결 세대의 이벤트를 적용하지 않는다")
    void receive_FencesRuntimeAndConnectionGeneration() throws Exception {
        // given
        state.activate(0);
        String event = telemetry(UUID.randomUUID().toString(), "default", pose);
        // when
        receiver.receive(event, 0);
        when(simulations.acceptsTelemetry("robot_1", "current")).thenReturn(true);
        state.invalidate(0); state.activate(1);
        receiver.receive(event, 0);
        // then
        verifyNoInteractions(stream);
        receiver.receive(event, 1);
        verify(stream).broadcast(pose);
    }

    @Test @DisplayName("중복 Ack와 종료 뒤 accepted 및 모순되는 종료를 차단한다")
    void receive_PreservesTerminalAck() throws Exception {
        // given
        state.activate(0);
        String cmd = UUID.randomUUID().toString();
        // when
        for (String status : new String[]{"accepted", "accepted", "done", "accepted", "failed"}) {
            AckDto ack = AckDto.of(cmd, "robot_1", status, status, OffsetDateTime.now());
            receiver.receive(mapper.writeValueAsString(new RealtimeReceiver.Envelope(1, "default", "ack", cmd + ":" + status, mapper.valueToTree(ack))), 0);
        }
        // then
        var values = ArgumentCaptor.forClass(AckDto.class);
        verify(messaging, times(2)).convertAndSend(eq("/topic/ack/robot_1"), values.capture());
        assertThat(values.getAllValues()).extracting(AckDto::status).containsExactly("accepted", "done");
    }

    @Test @DisplayName("잘못된 envelope와 payload를 전송하지 않는다")
    void receive_RejectsMalformedData() throws Exception {
        // given
        state.activate(0);
        // when, then
        assertThatThrownBy(() -> receiver.receive("null", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> receiver.receive(telemetry(UUID.randomUUID().toString(), "other", pose), 0)).isInstanceOf(IllegalArgumentException.class);
        String missingCoordinate = telemetry(UUID.randomUUID().toString(), "default", mapper.readTree("{\"type\":\"pose\",\"data\":{\"x\":1}}"));
        assertThatThrownBy(() -> receiver.receive(missingCoordinate, 0)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(stream, messaging);
    }

    @Test @DisplayName("중복 창은 시간과 크기를 제한하고 종료 상태를 보존한다")
    void deliveryWindow_ExpiresAndBoundsEntries() {
        // given
        AtomicLong now = new AtomicLong();
        var window = new DeliveryWindow(2, 100, now::get);
        // when, then
        assertThat(window.accept("a", null, null)).isTrue();
        assertThat(window.accept("a", null, null)).isFalse();
        assertThat(window.accept("b", null, null)).isTrue();
        assertThat(window.accept("c", null, null)).isTrue();
        assertThat(window.accept("a", null, null)).isTrue();
        now.set(100);
        assertThat(window.accept("a", null, null)).isTrue();
    }

    @Test @DisplayName("전달 경로 단절은 준비 상태를 해제하고 오래된 활성화를 거부한다")
    void state_FailsClosedAndRejectsStaleActivation() {
        // given
        state.activate(0);
        // when
        assertThat(state.invalidate(0)).isEqualTo(1);
        // then
        assertThatThrownBy(state::requireAvailable).isInstanceOf(ResponseStatusException.class);
        assertThat(state.activate(0)).isFalse();
        assertThat(state.activate(1)).isTrue();
    }
    @Test @DisplayName("준비 상태 변경과 무효화는 같은 monitor에서 직렬 처리한다")
    void state_SerializesInvalidationWithActivation() throws Exception {
        // given
        var entered = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<Long> invalidation;
            synchronized (state) {
                state.activate(0);
                invalidation = executor.submit(() -> { entered.countDown(); return state.invalidate(0); });
                assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
                // when, then
                assertThatThrownBy(() -> invalidation.get(50, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
            }
            assertThat(invalidation.get(1, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(state.available()).isFalse();
            assertThat(state.activate(0)).isFalse();
        }
    }

    @Test @DisplayName("status 검증기의 길이 경계를 확인한다")
    void validation_BoundsStatusLength() {
        // given
        var data = mapper.createObjectNode();
        // when, then
        data.put("state", "a".repeat(RealtimeValidation.MAX_STATE_LENGTH));
        assertThatCode(() -> RealtimeValidation.data("status", data)).doesNotThrowAnyException();
        data.put("state", "a".repeat(RealtimeValidation.MAX_STATE_LENGTH + 1));
        assertThatThrownBy(() -> RealtimeValidation.data("status", data)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test @DisplayName("Redis status 인입도 상한을 초과한 state를 전송하지 않는다")
    void receive_RejectsOversizedStatus() throws Exception {
        state.activate(0);
        var status = StatusTelemetryDto.of("robot_1", OffsetDateTime.now(), "x".repeat(RealtimeValidation.MAX_STATE_LENGTH + 1));
        String encoded = telemetry(UUID.randomUUID().toString(), "default", status);
        assertThatThrownBy(() -> receiver.receive(encoded, 0)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(stream, messaging);
    }

    @Test @DisplayName("환경 변수 없이도 fence deadline의 트랜잭션 계약을 검증한다")
    void fence_DeclaresReadOnlyDeadline() throws Exception {
        var attribute = new AnnotationTransactionAttributeSource()
                .getTransactionAttribute(SimulationService.class.getMethod("acceptsTelemetry", String.class, String.class), SimulationService.class);
        assertThat(attribute).isNotNull();
        assertThat(attribute.isReadOnly()).isTrue();
        assertThat(attribute.getTimeout()).isEqualTo(1);
    }

}
