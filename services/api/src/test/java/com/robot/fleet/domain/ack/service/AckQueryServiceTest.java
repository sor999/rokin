package com.robot.fleet.domain.ack.service;

import com.robot.fleet.domain.ack.controller.AckController;
import com.robot.fleet.domain.ack.entity.RobotAck;
import com.robot.fleet.domain.ack.repository.RobotAckRepository;
import com.robot.fleet.domain.simulation.repository.SimulationRepository;
import com.robot.fleet.global.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.OffsetDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class AckQueryServiceTest {
    private final RobotAckRepository repository = mock(RobotAckRepository.class);
    private final SimulationRepository simulations = mock(SimulationRepository.class);
    private final AckQueryService service = new AckQueryService(repository, simulations, "default");
    private final UUID cmd = UUID.randomUUID();

    @Test @DisplayName("현재 명령의 영속 Ack를 DTO로 조회하며 없으면 404를 반환한다")
    void find_ReturnsKnownCommandAckOr404() throws Exception {
        // given
        var ack = mock(RobotAck.class);
        when(ack.getCmdId()).thenReturn(cmd); when(ack.getRobotId()).thenReturn("robot_1");
        when(ack.getStatus()).thenReturn("done"); when(ack.getOccurredAt()).thenReturn(OffsetDateTime.now());
        when(simulations.findOwner("robot_1")).thenReturn(Optional.of("default"));
        when(repository.findTopByRobotIdAndCmdIdOrderByIdDesc("robot_1", cmd)).thenReturn(Optional.of(ack));
        var mvc = MockMvcBuilders.standaloneSetup(new AckController(service)).setControllerAdvice(new GlobalExceptionHandler()).build();
        // when, then
        mvc.perform(get("/api/robots/robot_1/acks/" + cmd)).andExpect(status().isOk()).andExpect(jsonPath("status").value("done"));
        mvc.perform(get("/api/robots/robot_1/acks/not-a-uuid")).andExpect(status().isBadRequest());
        mvc.perform(get("/api/robots/robot_2/acks/" + cmd)).andExpect(status().isNotFound());
    }

    @Test @DisplayName("다른 Fleet의 로봇 Ack는 읽지 않는다")
    void find_FencesFleetOwnership() {
        // given
        when(simulations.findOwner("robot_1")).thenReturn(Optional.of("other"));
        // when, then
        assertThat(service.find("robot_1", cmd)).isEmpty();
        verifyNoInteractions(repository);
    }
}
