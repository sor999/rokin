package com.robot.fleet.domain.simulation.dto;

public record SimulationReport(String robotId,long version,String state,String error,String runtimeId,Long runtimeEpoch) {
    public SimulationReport(String robotId,long version,String state,String error,String runtimeId) {this(robotId,version,state,error,runtimeId,null);}
}
