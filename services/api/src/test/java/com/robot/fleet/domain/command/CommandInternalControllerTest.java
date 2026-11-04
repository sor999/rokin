package com.robot.fleet.domain.command;

import com.robot.fleet.domain.command.controller.CommandInternalController;
import com.robot.fleet.domain.command.service.*;
import com.robot.fleet.global.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.http.MediaType;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class CommandInternalControllerTest {
    @Test void internalEndpointsRequireServerToken() throws Exception {
        var service=mock(DurableCommandService.class);
        var mvc=MockMvcBuilders.standaloneSetup(new CommandInternalController(service,mock(CommandDeliveryMetrics.class),"test-only"))
            .setControllerAdvice(new GlobalExceptionHandler()).build();
        for(String path:java.util.List.of("received","ack-receipts")) {
            mvc.perform(post("/internal/commands/"+path).contentType(MediaType.APPLICATION_JSON).content("[]")).andExpect(status().isUnauthorized());
            mvc.perform(post("/internal/commands/"+path).header("X-Simulator-Token","wrong").contentType(MediaType.APPLICATION_JSON).content("[]")).andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(service);
        mvc.perform(post("/internal/commands/received").header("X-Simulator-Token","test-only").contentType(MediaType.APPLICATION_JSON).content("[]")).andExpect(status().isNoContent());
        verify(service).received(java.util.List.of());
    }
}
