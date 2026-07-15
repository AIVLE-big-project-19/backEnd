package com.example.demo.global.response;

import lombok.Getter;

@Getter
public enum SuccessCode {

    // Board
    BOARD_CREATED("게시글이 등록되었습니다."),
    BOARD_UPDATED("게시글이 수정되었습니다."),
    BOARD_DELETED("게시글이 삭제되었습니다."),
    BOARD_FOUND("게시글 조회 성공"),
    BOARD_LIST_FOUND("게시글 목록 조회 성공"),

    // Comment
    COMMENT_CREATED("댓글이 등록되었습니다."),
    COMMENT_UPDATED("댓글이 수정되었습니다."),
    COMMENT_DELETED("댓글이 삭제되었습니다."),
    COMMENT_LIST_FOUND("댓글 목록 조회 성공"),

    // User
    USER_LOGIN("로그인 성공"),
    USER_SIGNUP("회원가입 성공"),
    USER_LOGIN_ID_CHECKED("아이디 중복확인 완료"),
    EMAIL_CODE_SENT("인증번호가 발송되었습니다."),
    EMAIL_CODE_VERIFIED("인증이 완료되었습니다."),
    TOKEN_REFRESHED("토큰이 재발급되었습니다."),
    USER_LOGOUT("로그아웃 되었습니다."),
    FIND_ID_CODE_SENT("인증번호가 발송되었습니다."),
    FIND_ID_FOUND("아이디 조회 성공"),
    PASSWORD_CODE_SENT("인증번호가 발송되었습니다."),
    PASSWORD_CODE_VERIFIED("인증이 완료되었습니다."),
    PASSWORD_VERIFICATION_STATUS_CHECKED("인증 상태 조회 성공"),
    PASSWORD_RESET("비밀번호가 재설정되었습니다."),

    // Chat
    CHAT_REPLIED("답변을 생성했습니다.");

    private final String message;

    SuccessCode(String message) {
        this.message = message;
    }

}