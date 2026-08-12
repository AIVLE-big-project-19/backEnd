package com.example.demo.dashboard.dto;

import com.example.demo.report.dto.BusinessRoute;
import com.example.demo.report.dto.RecommendedSubsidy;
import com.example.demo.report.dto.RegulatoryAssessment;

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
        EconomicAssumptions economicAssumptions,
        Long annualGenerationKwh,
        Long estimatedAnnualRevenue,
        Double roiPercent,
        Double paybackPeriodYears,
        GenerationForecast generationForecast,
        ScoreBreakdown scores,
        RoofAnalysis roofAnalysis,
        List<RiskItem> risks,
        List<ChecklistAction> checklist,
        RegulatoryAssessment regulatoryAssessment,
        BusinessRoute businessRoute,
        List<RecommendedSubsidy> subsidyRecommendations,
        String agentCaution,
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

    public record EconomicAssumptions(
            String registeredType,
            Long installationCostPerKw,
            Long estimatedInstallationCost,
            Double annualOmRatePercent,
            Long estimatedAnnualOmCost,
            Long estimatedAnnualNetIncome
    ) {}

    public record GenerationForecast(
            String source,
            String method,
            Integer capacityKw,
            Double tiltDegrees,
            Double azimuthDegrees,
            Double systemLossPercent,
            Double pvoutAvgDaily,
            Double specificYieldKwhPerKwpYear,
            boolean fallback,
            List<MonthlyGeneration> monthly,
            Long annualGenerationKwh
    ) {}

    public record MonthlyGeneration(Integer month, Long generationKwh) {}

    public record ScoreBreakdown(
            Integer ml,
            Integer vision,
            Integer regulation,
            String mlReason,
            String visionReason,
            String regulationReason
    ) {}

    public record RoofAnalysis(
            String type,
            String structure,
            Double slopeDegrees,
            Double shadowRate,
            Double shadowAreaM2,
            String moduleDirection,
            Double installAngleDegrees,
            Double roadDistanceM,
            Double buildingDistanceM,
            Double shapeEfficiency,
            Integer estimatedPanelCount
    ) {}

    public record RiskItem(String key, String label, String status, String level, String detail) {}

    public record ChecklistAction(String key, String title, String detail) {}
}
