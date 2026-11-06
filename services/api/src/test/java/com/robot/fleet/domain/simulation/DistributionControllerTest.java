package com.robot.fleet.domain.simulation;

import com.robot.fleet.domain.simulation.controller.DistributionController;
import com.robot.fleet.domain.simulation.service.DistributionService;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DistributionControllerTest {
    @Test
    void internalRoutesRequireTheServerTokenBeforeInvokingService() throws Exception {
        var service = mock(DistributionService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new DistributionController(service, "test-only")).build();
        mvc.perform(get("/internal/distribution/servers")).andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/distribution/pulse")
                .contentType("application/json").content("{\"runtimeId\":\"wrong\",\"capacity\":2}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/distribution/servers/old/drain"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/distribution/released")
                .contentType("application/json").content("{\"runtimeId\":\"old\",\"releases\":[]}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
        mvc.perform(get("/internal/distribution/servers").header("X-Simulator-Token", "test-only"))
                .andExpect(status().isOk());
        verify(service).servers();
    }
}
