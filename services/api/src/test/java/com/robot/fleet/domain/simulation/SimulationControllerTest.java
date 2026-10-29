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
        var dto = new com.robot.fleet.domain.simulation.dto.SimulationDto(
                "robot_4", "default", 0, 0, 1, "RUNNING", 1, "STARTING", null, null, null);
        when(service.create(any())).thenReturn(dto);
        when(service.delete("robot_4")).thenReturn(new com.robot.fleet.domain.simulation.dto.SimulationDto(
                "robot_4", "default", 0, 0, 1, "DELETED", 2, "DELETING", null, null, null));
        // when, then
        mvc.perform(post("/api/simulations").contentType("application/json").content(body)).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.robotId").value("robot_4")).andExpect(jsonPath("$.version").value(1));
        mvc.perform(delete("/api/simulations/robot_4")).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.robotId").value("robot_4"))
                .andExpect(jsonPath("$.version").value(2)).andExpect(jsonPath("$.desiredState").value("DELETED"));
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
    @Test @DisplayName("인증된 실행 상태 보고는 204로 수락하고 서비스에 내용을 전달한다")
    void internalReport_DelegatesToService() throws Exception {
        // given
        String runtime = "00000000-0000-0000-0000-000000000001";
        String body = "[{\"robotId\":\"robot_4\",\"version\":1,\"state\":\"RUNNING\",\"runtimeId\":\"" + runtime + "\"}]";
        // when
        mvc.perform(post("/internal/simulations/reports").header("X-Simulator-Token", "test-only-token")
                .contentType("application/json").content(body)).andExpect(status().isNoContent());
        // then
        verify(service).report(new com.robot.fleet.domain.simulation.dto.SimulationReport("robot_4", 1, "RUNNING", null, runtime));
    }

    @Test @DisplayName("런타임 등록은 토큰 없이 거부하고 인증 후에만 서비스에 전달한다")
    void internalRuntime_RequiresToken() throws Exception {
        // given
        String runtime = "00000000-0000-0000-0000-000000000001";
        String body = "{\"runtimeId\":\"" + runtime + "\"}";
        // when, then
        mvc.perform(post("/internal/simulations/runtime").contentType("application/json").content(body))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
        mvc.perform(post("/internal/simulations/runtime").header("X-Simulator-Token", "test-only-token")
                .contentType("application/json").content(body)).andExpect(status().isNoContent());
        verify(service).registerRuntime(runtime);
    }

}
