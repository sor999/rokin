package com.robot.fleet.domain.simulation.dto;

public record SimulationReport(String robotId, long version, String state, String error, String runtimeId) { }
