package com.example.demo.global.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum ErrorCode {

    BOARD_NOT_FOUND(HttpStatus.NOT_FOUND, "게시글을 찾을 수 없습니다."),
    COMMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "댓글을 찾을 수 없습니다."),
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "입력값이 올바르지 않습니다.");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message){
        this.status = status;
        this.message = message;
    }

}