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
    USER_SIGNUP("회원가입 성공");

    private final String message;

    SuccessCode(String message) {
        this.message = message;
    }

}