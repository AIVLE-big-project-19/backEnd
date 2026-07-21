package com.example.demo.board.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class BoardAttachmentResponse {
    private Long attachmentId;
    private String originalFilename;
    private String contentType;
    private long fileSize;
    private boolean image;
}
