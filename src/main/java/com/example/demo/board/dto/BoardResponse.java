package com.example.demo.board.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Builder
public class BoardResponse {

    private Long boardId;

    private String title;

    private String content;

    private String writer;

    private String writerName;

    private Boolean owner;

    private String category;

    private Integer viewCount;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private List<BoardAttachmentResponse> attachments;

}
