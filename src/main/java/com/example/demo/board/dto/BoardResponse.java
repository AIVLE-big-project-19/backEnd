package com.example.demo.board.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class BoardResponse {

    private Long boardId;

    private String title;

    private String content;

    private String writer;

    private String category;

    private Integer viewCount;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

}