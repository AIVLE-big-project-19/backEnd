package com.example.demo.global.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum ErrorCode {

    COMMENT_ACCESS_DENIED(HttpStatus.FORBIDDEN, "본인이 작성한 댓글만 수정하거나 삭제할 수 있습니다."),

    BOARD_NOT_FOUND(HttpStatus.NOT_FOUND, "게시글을 찾을 수 없습니다."),
    COMMENT_NOT_FOUND(HttpStatus.NOT_FOUND, "댓글을 찾을 수 없습니다."),
    INVALID_INPUT(HttpStatus.BAD_REQUEST, "입력값이 올바르지 않습니다."),

    DUPLICATE_LOGIN_ID(HttpStatus.CONFLICT, "이미 사용 중인 아이디입니다."),
    DUPLICATE_EMAIL(HttpStatus.CONFLICT, "이미 가입된 이메일입니다."),
    EMAIL_VERIFICATION_REQUIRED(HttpStatus.BAD_REQUEST, "이메일 인증이 필요합니다."),
    INVALID_VERIFICATION_CODE(HttpStatus.BAD_REQUEST, "인증번호가 일치하지 않거나 만료되었습니다."),
    EMAIL_CODE_COOLDOWN(HttpStatus.TOO_MANY_REQUESTS, "잠시 후 다시 시도해주세요."),
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "아이디 또는 비밀번호가 일치하지 않습니다."),
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "로그인이 만료되었습니다. 다시 로그인해주세요."),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "일치하는 회원 정보를 찾을 수 없습니다."),
    IDENTITY_NOT_VERIFIED(HttpStatus.FORBIDDEN, "본인 인증이 필요합니다."),
    CHAT_REQUEST_FAILED(HttpStatus.BAD_GATEWAY, "챗봇 응답 생성에 실패했습니다."),
    GOOGLE_AUTH_FAILED(HttpStatus.BAD_GATEWAY, "구글 인증에 실패했습니다."),
    EMAIL_ALREADY_REGISTERED_AS_LOCAL(HttpStatus.CONFLICT, "이미 일반 회원가입으로 등록된 이메일입니다. 일반 로그인을 이용해주세요."),
    TERMS_NOT_FOUND(HttpStatus.NOT_FOUND, "약관을 찾을 수 없습니다."),
    ACCOUNT_LOCKED(HttpStatus.LOCKED, "로그인 시도 횟수를 초과하여 계정이 일시적으로 잠겼습니다.");

    private final HttpStatus status;
    private final String message;

    ErrorCode(HttpStatus status, String message){
        this.status = status;
        this.message = message;
    }

}
