package com.example.demo.recommend.dto;

import java.time.LocalDateTime;

public record RecommendationHistoryResponse(
        Long id,
        String originalFilename,
        String status,
        String stage,
        String errorMessage,
        LocalDateTime createdAt
) {
}
