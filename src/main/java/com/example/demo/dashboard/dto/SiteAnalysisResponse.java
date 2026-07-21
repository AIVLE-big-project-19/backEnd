package com.example.demo.dashboard.dto;

import com.example.demo.dashboard.entity.SiteAnalysis;

import java.time.LocalDateTime;

public record SiteAnalysisResponse(
        Long id, String address, Double latitude, Double longitude, Double areaM2, Double capacityKw,
        Integer suitabilityScore, String grade, Integer irradiationScore, Integer terrainScore, Integer accessScore,
        Double annualGenerationKwh, Long estimatedInstallationCost, Long estimatedAnnualRevenue,
        Double paybackPeriodYears, LocalDateTime createdAt
) {
    public static SiteAnalysisResponse from(SiteAnalysis analysis) {
        int score = analysis.getSuitabilityScore();
        String grade = score >= 80 ? "적합" : score >= 60 ? "검토 필요" : "부적합";
        return new SiteAnalysisResponse(
                analysis.getId(), analysis.getAddress(), analysis.getLatitude(), analysis.getLongitude(),
                analysis.getAreaM2(), analysis.getCapacityKw(), score, grade, analysis.getIrradiationScore(),
                analysis.getTerrainScore(), analysis.getAccessScore(), analysis.getAnnualGenerationKwh(),
                analysis.getEstimatedInstallationCost(), analysis.getEstimatedAnnualRevenue(),
                analysis.getPaybackPeriodYears(), analysis.getCreatedAt());
    }
}
