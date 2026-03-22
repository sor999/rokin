package com.robot.fleet.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // 400
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, "잘못된 요청입니다."),

    // 404
    ROBOT_NOT_FOUND(HttpStatus.NOT_FOUND, "로봇을 찾을 수 없습니다."),

    // 500
    COMMAND_SEND_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "명령 전송에 실패했습니다."),
    INTERNAL_SERVER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "서버 오류가 발생했습니다.");

    private final HttpStatus status;
    private final String message;
}
