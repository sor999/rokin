package com.robot.fleet.domain.simulation.dto;

public record CreateSimulationRequest(String robotId, Double startX, Double startY, Double speed) {
    public void validate() {
        if (robotId == null || !robotId.matches("[A-Za-z][A-Za-z0-9_]{0,63}")) {
            throw new IllegalArgumentException("로봇 ID는 영문자로 시작하는 영문·숫자·밑줄 1~64자입니다.");
        }
        if (startX == null || startY == null || speed == null || !Double.isFinite(startX)
                || !Double.isFinite(startY) || !Double.isFinite(speed) || speed <= 0 || speed > 10) {
            throw new IllegalArgumentException("좌표는 유한한 숫자, 속도는 0 초과 10 이하로 입력하세요.");
        }
    }
}
