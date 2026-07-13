package com.example.demo.comment.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class CommentResponse {

    private Long commentId;

    private Long boardId;

    private String writer;

    private String content;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

}