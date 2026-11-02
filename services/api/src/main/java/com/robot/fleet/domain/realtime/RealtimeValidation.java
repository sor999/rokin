package com.robot.fleet.domain.realtime;

import com.fasterxml.jackson.databind.JsonNode;

public final class RealtimeValidation {
    private RealtimeValidation() { }
    public static void data(String type, JsonNode data) {
        if (data == null || !data.isObject()) throw new IllegalArgumentException("Missing telemetry data");
        switch (type) {
            case "pose" -> { number(data, "x"); number(data, "y"); }
            case "battery" -> {
                number(data, "level");
                double level = data.get("level").asDouble();
                if (level < 0 || level > 100) throw new IllegalArgumentException("Invalid battery");
            }
            case "status" -> {
                if (!data.path("state").isTextual() || data.path("state").asText().isBlank()) throw new IllegalArgumentException("Invalid state");
            }
            default -> throw new IllegalArgumentException("Invalid telemetry type");
        }
    }
    private static void number(JsonNode data, String name) {
        if (!data.path(name).isNumber() || !Double.isFinite(data.path(name).asDouble())) throw new IllegalArgumentException("Invalid coordinate");
    }
}
