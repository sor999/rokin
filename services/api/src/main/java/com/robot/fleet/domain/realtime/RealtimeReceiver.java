package com.robot.fleet.domain.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.robot.fleet.domain.ack.dto.AckDto;
import com.robot.fleet.domain.simulation.service.SimulationService;
import com.robot.fleet.domain.telemetry.dto.*;
import com.robot.fleet.domain.telemetry.service.TelemetryStreamService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
public class RealtimeReceiver {
    public record Envelope(int version, String fleetId, String kind, String id, JsonNode payload) { }
    private final String fleetId;
    private final ObjectMapper mapper;
    private final SimulationService simulations;
    private final TelemetryStreamService stream;
    private final SimpMessagingTemplate messaging;
    private final RealtimeState state;
    private final DeliveryWindow window = new DeliveryWindow(10000, 300000, System::currentTimeMillis);

    public RealtimeReceiver(@Value("${fleet.id:default}") String fleetId, ObjectMapper mapper,
                            SimulationService simulations, TelemetryStreamService stream,
                            SimpMessagingTemplate messaging, RealtimeState state) {
        this.fleetId = fleetId; this.mapper = mapper; this.simulations = simulations;
        this.stream = stream; this.messaging = messaging; this.state = state;
    }

    public void receive(String encoded, long generation) throws Exception {
        if (!state.available() || state.generation() != generation) return;
        Envelope message = mapper.readValue(encoded, Envelope.class);
        if (message == null || message.id() == null || message.version() != 1 || !fleetId.equals(message.fleetId()) || message.payload() == null || message.payload().isNull()) {
            throw new IllegalArgumentException("Invalid realtime envelope");
        }
        if ("telemetry".equals(message.kind())) {
            UUID.fromString(message.id());
            JsonNode data = message.payload();
            RealtimeValidation.data(data.path("type").asText(), data.path("data"));
            TelemetryEvent event = switch (data.path("type").asText()) {
                case "pose" -> mapper.treeToValue(data, PoseTelemetryDto.class);
                case "battery" -> mapper.treeToValue(data, BatteryTelemetryDto.class);
                case "status" -> mapper.treeToValue(data, StatusTelemetryDto.class);
                default -> throw new IllegalArgumentException("Invalid telemetry type");
            };
            if (event.occurredAt() == null || event.data() == null || event.robotId() == null || !event.robotId().matches("[A-Za-z][A-Za-z0-9_-]{0,127}")) throw new IllegalArgumentException("Invalid telemetry");
            if (!simulations.acceptsTelemetry(event.robotId(), event.runtimeId())) return;
            state.ifCurrent(generation, () -> {
                if (window.accept("telemetry:" + message.id(), null, null)) stream.broadcast(event);
            });
        } else if ("ack".equals(message.kind())) {
            AckDto ack = mapper.treeToValue(message.payload(), AckDto.class);
            if (ack.cmdId() == null || ack.status() == null) throw new IllegalArgumentException("Invalid ack");
            UUID.fromString(ack.cmdId());
            if (ack.robotId() == null || !ack.robotId().matches("[A-Za-z][A-Za-z0-9_-]{0,127}")
                    || ack.occurredAt() == null || !List.of("accepted", "done", "failed").contains(ack.status())
                    || !message.id().equals(ack.cmdId() + ":" + ack.status())) throw new IllegalArgumentException("Invalid ack");
            state.ifCurrent(generation, () -> {
                if (window.accept("ack:" + message.id(), ack.cmdId(), ack.status())) {
                    messaging.convertAndSend("/topic/ack/" + ack.robotId(), ack);
                }
            });
        } else throw new IllegalArgumentException("Invalid realtime kind");
    }
}
