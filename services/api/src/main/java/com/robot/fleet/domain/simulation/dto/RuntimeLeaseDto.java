package com.robot.fleet.domain.simulation.dto;

public record RuntimeLeaseDto(String fleetId, String runtimeId, long epoch,
                              boolean granted, boolean retired, long ttlMillis) { }
