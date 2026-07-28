package com.example.demo.recommend.dto;

import com.example.demo.report.dto.AiAnalysisResponse;

import java.util.List;
import java.util.Map;

public record RecommendationStatusResponse(
        Long id,
        String status,
        String stage,
        Map<String, Object> funnel,
        List<AiAnalysisResponse> recommendations,
        String errorMessage
) {
}
