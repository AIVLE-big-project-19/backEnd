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

    private String writerName;

    private Boolean owner;

    private String content;

    private Boolean secret;

    private Boolean canView;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

}
