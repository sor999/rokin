package com.robot.fleet.domain.simulation;

import com.robot.fleet.domain.simulation.controller.*;
import com.robot.fleet.domain.simulation.service.SimulationService;
import com.robot.fleet.global.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class SimulationControllerTest {
    SimulationService service;
    MockMvc mvc;

    @BeforeEach
    void prepare() {
        service = mock(SimulationService.class);
        mvc = MockMvcBuilders.standaloneSetup(new SimulationController(service),
                new SimulationInternalController(service, "test-only-token", "default"))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test @DisplayName("생성·삭제 요청은 비동기 접수하고 잘못된 JSON은 거부한다")
    void publicApi_ReturnsAccepted() throws Exception {
        // given
        String body = "{\"robotId\":\"robot_4\",\"startX\":0,\"startY\":0,\"speed\":1}";
        // when, then
        mvc.perform(post("/api/simulations").contentType("application/json").content(body)).andExpect(status().isAccepted());
        mvc.perform(delete("/api/simulations/robot_4")).andExpect(status().isAccepted());
        mvc.perform(post("/api/simulations").contentType("application/json").content("{")).andExpect(status().isBadRequest());
    }

    @Test @DisplayName("내부 관리 API는 토큰 없이 조회와 보고를 허용하지 않는다")
    void internalApi_RequiresToken() throws Exception {
        // given
        String body = "[{\"robotId\":\"robot\",\"version\":1,\"state\":\"RUNNING\"}]";
        // when, then
        mvc.perform(get("/internal/simulations")).andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/simulations/reports").contentType("application/json").content(body))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
        when(service.list()).thenReturn(java.util.List.of());
        mvc.perform(get("/internal/simulations").header("X-Simulator-Token", "test-only-token"))
                .andExpect(status().isOk());
    }
}
