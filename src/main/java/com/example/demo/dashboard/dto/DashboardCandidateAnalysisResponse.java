package com.example.demo.dashboard.dto;

import java.util.List;

public record DashboardCandidateAnalysisResponse(
        Long id,
        String sourceId,
        String address,
        String siteType,
        Double latitude,
        Double longitude,
        Double areaM2,
        Double usableRoofAreaM2,
        Double roofUtilizationRate,
        Integer suitabilityScore,
        String grade,
        String priorityRank,
        Integer capacityKw,
        CapacityEstimate capacityEstimate,
        Long annualGenerationKwh,
        Long estimatedAnnualRevenue,
        Double roiPercent,
        Double paybackPeriodYears,
        GenerationForecast generationForecast,
        ScoreBreakdown scores,
        RoofAnalysis roofAnalysis,
        List<RiskItem> risks,
        List<ChecklistAction> checklist,
        Long analysisId
) {
    public record CapacityEstimate(
            String registeredType,
            String visionType,
            Double availableAreaM2,
            Double areaPerKwM2,
            String formula,
            String source
    ) {}

    public record GenerationForecast(
            String source,
            String method,
            Integer capacityKw,
            Double tiltDegrees,
            Double azimuthDegrees,
            Double systemLossPercent,
            boolean fallback,
            List<MonthlyGeneration> monthly,
            Long annualGenerationKwh
    ) {}

    public record MonthlyGeneration(Integer month, Long generationKwh) {}

    public record ScoreBreakdown(Integer ml, Integer vision, Integer regulation) {}

    public record RoofAnalysis(
            String type,
            String structure,
            Double slopeDegrees,
            Double shadowRate,
            Double shadowAreaM2,
            String moduleDirection,
            Double installAngleDegrees
    ) {}

    public record RiskItem(String key, String label, String status, String level, String detail) {}

    public record ChecklistAction(String key, String title, String detail) {}
}
