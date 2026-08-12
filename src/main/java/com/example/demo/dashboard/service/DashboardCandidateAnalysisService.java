package com.example.demo.dashboard.service;

import com.example.demo.dashboard.client.PvgisClient;
import com.example.demo.dashboard.dto.DashboardCandidateAnalysisResponse;
import com.example.demo.analysis.service.AnalysisSnapshotService;
import com.example.demo.global.exception.CustomException;
import com.example.demo.global.exception.ErrorCode;
import com.example.demo.idleland.client.MlScoringClient;
import com.example.demo.idleland.dto.MlRankResponse;
import com.example.demo.idleland.entity.IdleLand;
import com.example.demo.idleland.repository.IdleLandRepository;
import com.example.demo.idleland.service.VisionEnrichmentService;
import com.example.demo.idleland.support.EconomicsCalculator;
import com.example.demo.report.dto.AiAnalysisResponse;
import com.example.demo.report.dto.ChecklistItem;
import com.example.demo.report.dto.DetailScores;
import com.example.demo.report.dto.RiskCheck;
import com.example.demo.report.dto.ScoresAndEvaluation;
import com.example.demo.report.dto.SiteInfo;
import com.example.demo.report.dto.VisionAiSimulation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class DashboardCandidateAnalysisService {

    private static final double LAND_AREA_PER_KW_M2 = 10d;
    private static final double ROOF_AREA_PER_KW_M2 = 7.5d;
    private static final long GENERATION_PER_KW = 1_300L;
    private static final double DEFAULT_TILT_DEGREES = 30d;
    private static final double[] MONTHLY_FALLBACK_WEIGHTS = {
            0.62d, 0.70d, 0.88d, 1.02d, 1.14d, 1.20d,
            1.18d, 1.08d, 0.94d, 0.82d, 0.68d, 0.60d
    };

    private final IdleLandRepository idleLandRepository;
    private final MlScoringClient mlScoringClient;
    private final VisionEnrichmentService visionEnrichmentService;
    private final PvgisClient pvgisClient;
    private final AnalysisSnapshotService analysisSnapshotService;

    public DashboardCandidateAnalysisResponse analyze(Long idleLandId, Long userId) {
        IdleLand idleLand = idleLandRepository.findById(idleLandId)
                .orElseThrow(() -> new CustomException(ErrorCode.IDLE_LAND_NOT_FOUND));
        String datasetType = "BUILDING".equals(idleLand.getAssetTypeNorm()) ? "building" : "land";

        MlRankResponse response = mlScoringClient.rank(datasetType, List.of(idleLand), 1, true);
        AiAnalysisResponse analysis = response.getTopCandidates() == null || response.getTopCandidates().isEmpty()
                ? null
                : response.getTopCandidates().get(0);
        if (analysis == null) {
            throw new CustomException(ErrorCode.ML_SERVER_REQUEST_FAILED, "ML 서버가 상세 분석 결과를 반환하지 않았습니다.");
        }

        visionEnrichmentService.enrich(idleLand, analysis);
        DashboardCandidateAnalysisResponse dashboardResponse = toResponse(idleLand, analysis);
        Long analysisId = analysisSnapshotService.save(userId, idleLand, analysis, dashboardResponse);
        return new DashboardCandidateAnalysisResponse(
                dashboardResponse.id(), dashboardResponse.sourceId(), dashboardResponse.address(), dashboardResponse.siteType(),
                dashboardResponse.latitude(), dashboardResponse.longitude(), dashboardResponse.areaM2(), dashboardResponse.usableRoofAreaM2(),
                dashboardResponse.roofUtilizationRate(), dashboardResponse.suitabilityScore(), dashboardResponse.grade(),
                dashboardResponse.priorityRank(), dashboardResponse.capacityKw(), dashboardResponse.capacityEstimate(),
                dashboardResponse.economicAssumptions(),
                dashboardResponse.annualGenerationKwh(), dashboardResponse.estimatedAnnualRevenue(), dashboardResponse.roiPercent(),
                dashboardResponse.paybackPeriodYears(), dashboardResponse.generationForecast(), dashboardResponse.scores(),
                dashboardResponse.roofAnalysis(), dashboardResponse.risks(), dashboardResponse.checklist(),
                dashboardResponse.regulatoryAssessment(), dashboardResponse.businessRoute(),
                dashboardResponse.subsidyRecommendations(), dashboardResponse.agentCaution(), analysisId
        );
    }

    private DashboardCandidateAnalysisResponse toResponse(IdleLand idleLand, AiAnalysisResponse analysis) {
        SiteInfo site = analysis.getSiteInfo();
        ScoresAndEvaluation evaluation = analysis.getScoresAndEvaluation();
        DetailScores details = evaluation == null ? null : evaluation.getDetailScores();
        VisionAiSimulation vision = analysis.getVisionAiSimulation();
        Double availableArea = site == null ? null : site.getAvailableArea();
        Map<String, Object> visionValues = vision == null || vision.getVisionAnalysis() == null
                ? Map.of()
                : vision.getVisionAnalysis();
        String siteType = registeredSiteType(idleLand.getAssetTypeNorm());
        DashboardCandidateAnalysisResponse.RoofAnalysis roofAnalysis =
                toRoofAnalysis(visionValues, siteType, site);
        CapacityResolution capacityResolution = resolveCapacity(siteType, visionValues, availableArea);
        Integer capacityKw = capacityResolution.capacityKw();
        Optional<PvgisClient.Forecast> pvgisForecast = fetchGenerationForecast(
                idleLand,
                siteType,
                roofAnalysis,
                capacityKw
        );
        Long resolvedAnnualGenerationKwh = resolveAnnualGeneration(
                capacityKw,
                pvgisForecast.map(PvgisClient.Forecast::annualGenerationKwh).orElse(null),
                idleLand.getPvoutAvgDaily()
        );
        EconomicEstimate economics = resolveEconomics(
                siteType,
                capacityKw,
                resolvedAnnualGenerationKwh
        );
        DashboardCandidateAnalysisResponse.GenerationForecast generationForecast = pvgisForecast
                .map(this::toGenerationForecast)
                .orElseGet(() -> fallbackGenerationForecast(
                        economics,
                        roofAnalysis,
                        idleLand.getPvoutAvgDaily()
                ));

        return new DashboardCandidateAnalysisResponse(
                idleLand.getId(),
                idleLand.getSourceId(),
                site != null && site.getAddress() != null ? site.getAddress() : idleLand.getAddress(),
                siteType,
                idleLand.getLatitude(),
                idleLand.getLongitude(),
                site == null ? null : site.getTotalArea(),
                availableArea,
                availabilityRate(site, availableArea),
                evaluation == null ? null : evaluation.getTotalScore(),
                evaluation == null ? null : evaluation.getGrade(),
                evaluation == null ? null : evaluation.getPriorityRank(),
                economics.capacityKw(),
                capacityResolution.estimate(),
                economics.assumptions(),
                economics.annualGenerationKwh(),
                economics.annualRevenueKrw(),
                economics.roiPercent(),
                economics.paybackYears(),
                generationForecast,
                new DashboardCandidateAnalysisResponse.ScoreBreakdown(
                        details == null ? null : details.getMlTechnicalScore(),
                        details == null ? null : details.getVisionAiScore(),
                        details == null ? null : details.getRuleBasedScore(),
                        details == null ? null : details.getMlReason(),
                        details == null ? null : details.getVisionReason(),
                        details == null ? null : details.getRuleReason()
                ),
                roofAnalysis,
                toRisks(analysis),
                toChecklist(analysis.getPreInvestigationChecklist()),
                analysis.getRiskAndSupport() == null ? null : analysis.getRiskAndSupport().getRegulatoryAssessment(),
                analysis.getRiskAndSupport() == null ? null : analysis.getRiskAndSupport().getBusinessRoute(),
                analysis.getRiskAndSupport() == null ? null : analysis.getRiskAndSupport().getRecommendedPrograms(),
                analysis.getRiskAndSupport() == null || analysis.getRiskAndSupport().getAgentExplanation() == null
                        ? null : analysis.getRiskAndSupport().getAgentExplanation().getCaution(),
                null
        );
    }

    private CapacityResolution resolveCapacity(
            String registeredType,
            Map<String, Object> visionValues,
            Double availableArea
    ) {
        double areaPerKwM2 = "ROOF".equals(registeredType)
                ? ROOF_AREA_PER_KW_M2
                : LAND_AREA_PER_KW_M2;
        String visionType = normalizeVisionType(text(visionValues.get("candidate_type"), null));
        Integer capacityKw = availableArea != null && availableArea > 0
                ? Math.max(3, (int) Math.round(availableArea / areaPerKwM2))
                : null;
        String source = capacityKw == null ? "AREA_UNAVAILABLE" : "REGISTERED_TYPE_AREA";

        return new CapacityResolution(
                capacityKw,
                new DashboardCandidateAnalysisResponse.CapacityEstimate(
                        registeredType,
                        visionType,
                        availableArea,
                        areaPerKwM2,
                        "max(3, round(availableAreaM2 / areaPerKwM2))",
                        source
                )
        );
    }

    private String normalizeVisionType(String visionType) {
        if (visionType == null || visionType.isBlank()) {
            return null;
        }
        return switch (visionType.trim().toUpperCase()) {
            case "BUILDING", "ROOF" -> "ROOF";
            case "LAND" -> "LAND";
            case "PARKING_LOT" -> "PARKING_LOT";
            default -> visionType.trim().toUpperCase();
        };
    }

    private EconomicEstimate resolveEconomics(
            String registeredType,
            Integer capacityKw,
            Long locationBasedAnnualGenerationKwh
    ) {
        Long annualGenerationKwh = locationBasedAnnualGenerationKwh;
        if (annualGenerationKwh == null && capacityKw != null) {
            annualGenerationKwh = capacityKw * GENERATION_PER_KW;
        }
        Long annualRevenueKrw = EconomicsCalculator.resolveAnnualRevenueKrw(annualGenerationKwh, null);

        EconomicsCalculator.RoiResult roi = EconomicsCalculator.calculateRoi(registeredType, capacityKw, annualRevenueKrw);

        DashboardCandidateAnalysisResponse.EconomicAssumptions assumptions =
                new DashboardCandidateAnalysisResponse.EconomicAssumptions(
                        registeredType,
                        EconomicsCalculator.installationCostPerKw(registeredType),
                        roi.installationCost(),
                        EconomicsCalculator.ANNUAL_OM_RATE * 100d,
                        roi.annualOmCost(),
                        roi.annualNetIncome()
                );

        return new EconomicEstimate(
                capacityKw,
                annualGenerationKwh,
                annualRevenueKrw,
                roi.roiPercent(),
                roi.paybackYears(),
                assumptions
        );
    }

    private Long resolveAnnualGeneration(
            Integer capacityKw,
            Long pvgisAnnualGenerationKwh,
            Double pvoutAvgDaily
    ) {
        if (pvgisAnnualGenerationKwh != null) {
            return pvgisAnnualGenerationKwh;
        }
        if (capacityKw == null) {
            return null;
        }
        if (pvoutAvgDaily != null && Double.isFinite(pvoutAvgDaily) && pvoutAvgDaily > 0) {
            return Math.round(capacityKw * pvoutAvgDaily * 365d);
        }
        return capacityKw * GENERATION_PER_KW;
    }

    private Optional<PvgisClient.Forecast> fetchGenerationForecast(
            IdleLand idleLand,
            String siteType,
            DashboardCandidateAnalysisResponse.RoofAnalysis roofAnalysis,
            Integer capacityKw
    ) {
        if (idleLand.getLatitude() == null || idleLand.getLongitude() == null || capacityKw == null) {
            return Optional.empty();
        }
        double tiltDegrees = roofAnalysis.installAngleDegrees() == null
                ? DEFAULT_TILT_DEGREES
                : roofAnalysis.installAngleDegrees();
        return pvgisClient.forecast(new PvgisClient.Request(
                idleLand.getLatitude(),
                idleLand.getLongitude(),
                capacityKw,
                tiltDegrees,
                toPvgisAzimuth(roofAnalysis.moduleDirection()),
                "ROOF".equals(siteType) ? "building" : "free"
        ));
    }

    private DashboardCandidateAnalysisResponse.GenerationForecast toGenerationForecast(PvgisClient.Forecast forecast) {
        return new DashboardCandidateAnalysisResponse.GenerationForecast(
                forecast.source(),
                forecast.method(),
                forecast.capacityKw(),
                forecast.tiltDegrees(),
                forecast.azimuthDegrees(),
                forecast.systemLossPercent(),
                null,
                forecast.capacityKw() > 0
                        ? roundOneDecimal(forecast.annualGenerationKwh() / (double) forecast.capacityKw())
                        : null,
                forecast.fallback(),
                forecast.monthly().stream()
                        .map(item -> new DashboardCandidateAnalysisResponse.MonthlyGeneration(
                                item.month(),
                                item.generationKwh()
                        ))
                        .toList(),
                forecast.annualGenerationKwh()
        );
    }

    private DashboardCandidateAnalysisResponse.GenerationForecast fallbackGenerationForecast(
            EconomicEstimate economics,
            DashboardCandidateAnalysisResponse.RoofAnalysis roofAnalysis,
            Double pvoutAvgDaily
    ) {
        if (economics.annualGenerationKwh() == null) {
            return null;
        }
        double totalWeight = 0d;
        for (double weight : MONTHLY_FALLBACK_WEIGHTS) {
            totalWeight += weight;
        }

        long remaining = economics.annualGenerationKwh();
        List<DashboardCandidateAnalysisResponse.MonthlyGeneration> monthly = new ArrayList<>();
        for (int index = 0; index < MONTHLY_FALLBACK_WEIGHTS.length; index++) {
            long generationKwh = index == MONTHLY_FALLBACK_WEIGHTS.length - 1
                    ? remaining
                    : Math.round(economics.annualGenerationKwh() * MONTHLY_FALLBACK_WEIGHTS[index] / totalWeight);
            remaining -= generationKwh;
            monthly.add(new DashboardCandidateAnalysisResponse.MonthlyGeneration(index + 1, generationKwh));
        }

        boolean usesPvout = pvoutAvgDaily != null && Double.isFinite(pvoutAvgDaily) && pvoutAvgDaily > 0;
        return new DashboardCandidateAnalysisResponse.GenerationForecast(
                usesPvout ? "후보지 pvout_avg_daily" : "고정 발전원단위",
                usesPvout ? "PVOUT_DAILY_SPECIFIC_YIELD" : "FIXED_SPECIFIC_YIELD_FALLBACK",
                economics.capacityKw(),
                roofAnalysis.installAngleDegrees() == null ? DEFAULT_TILT_DEGREES : roofAnalysis.installAngleDegrees(),
                toPvgisAzimuth(roofAnalysis.moduleDirection()),
                null,
                usesPvout ? pvoutAvgDaily : null,
                usesPvout ? roundOneDecimal(pvoutAvgDaily * 365d) : (double) GENERATION_PER_KW,
                true,
                monthly,
                economics.annualGenerationKwh()
        );
    }

    private double toPvgisAzimuth(String orientation) {
        if (orientation == null || orientation.isBlank()) {
            return 0d;
        }
        if (orientation.contains("남동")) return -45d;
        if (orientation.contains("남서")) return 45d;
        if (orientation.contains("북동")) return -135d;
        if (orientation.contains("북서")) return 135d;
        if (orientation.contains("동")) return -90d;
        if (orientation.contains("서")) return 90d;
        if (orientation.contains("북")) return 180d;
        return 0d;
    }

    private double roundOneDecimal(double value) {
        return Math.round(value * 10d) / 10d;
    }

    private record EconomicEstimate(
            Integer capacityKw,
            Long annualGenerationKwh,
            Long annualRevenueKrw,
            Double roiPercent,
            Double paybackYears,
            DashboardCandidateAnalysisResponse.EconomicAssumptions assumptions
    ) {}

    private record CapacityResolution(
            Integer capacityKw,
            DashboardCandidateAnalysisResponse.CapacityEstimate estimate
    ) {}

    private Double availabilityRate(SiteInfo site, Double availableArea) {
        if (site != null && site.getAvailabilityRatePercent() != null) {
            return site.getAvailabilityRatePercent();
        }
        if (site == null || site.getTotalArea() == null || site.getTotalArea() <= 0 || availableArea == null) {
            return null;
        }
        return Math.round(availableArea / site.getTotalArea() * 10_000d) / 100d;
    }

    private DashboardCandidateAnalysisResponse.RoofAnalysis toRoofAnalysis(
            Map<String, Object> values,
            String targetType,
            SiteInfo site
    ) {
        boolean roof = "ROOF".equalsIgnoreCase(targetType);
        return new DashboardCandidateAnalysisResponse.RoofAnalysis(
                text(values.get(roof ? "roof_structure_type" : "land_surface_type"),
                        roof ? "옥상형" : "토지형"),
                site == null ? null : site.getSpaceType(),
                number(values.get(roof ? "roof_slope_deg" : "slope_degree")),
                number(values.get(roof ? "obstacle_shading_ratio_percent" : "vegetation_coverage_percent")),
                number(values.get("obstacle_shading_area")),
                resolveDirection(values),
                number(values.get("recommended_tilt_angle_deg")),
                number(firstValue(values,
                        "distance_to_road_m", "road_distance_m", "roadDistanceM")),
                number(firstValue(values,
                        "distance_to_building_m", "building_distance_m", "buildingDistanceM")),
                number(firstValue(values,
                        "shape_efficiency", "shapeEfficiency")),
                integer(firstValue(values,
                        "estimated_panel_count", "panel_count", "estimatedPanelCount"), null)
        );
    }

    private Object firstValue(Map<String, Object> values, String... keys) {
        for (String key : keys) {
            Object value = values.get(key);
            if (value != null && (!(value instanceof String string) || !string.isBlank())) {
                return value;
            }
        }
        return null;
    }

    private String resolveDirection(Map<String, Object> values) {
        String recommended = text(values.get("recommended_orientation"), null);
        if (recommended != null && !recommended.isBlank()) {
            return recommended;
        }
        Object aspect = values.get("aspect_direction");
        if (aspect instanceof String direction && !direction.isBlank()) {
            return direction;
        }
        Double degrees = number(aspect != null ? aspect : values.get("aspect_direction_degree"));
        if (degrees == null) return null;
        String[] directions = {"북", "북동", "동", "남동", "남", "남서", "서", "북서"};
        int index = (int) Math.round(((degrees % 360d) + 360d) % 360d / 45d) % 8;
        return directions[index];
    }

    private List<DashboardCandidateAnalysisResponse.RiskItem> toRisks(AiAnalysisResponse analysis) {
        RiskCheck risk = analysis.getRiskAndSupport() == null
                ? null
                : analysis.getRiskAndSupport().getRuleBasedRiskCheck();
        if (risk == null) {
            return List.of();
        }

        List<DashboardCandidateAnalysisResponse.RiskItem> items = new ArrayList<>();
        addRisk(items, "grid", "전력 계통 연계", risk.getGridConnection());
        addRisk(items, "regulation", "인허가·규제", risk.getRegulation());
        addRisk(items, "complaint", "주변 민원 가능성", risk.getPublicComplaint());
        return items;
    }

    private void addRisk(
            List<DashboardCandidateAnalysisResponse.RiskItem> items,
            String key,
            String label,
            String value
    ) {
        if (value == null || value.isBlank()) {
            return;
        }
        String level = isPositive(value) ? "good" : "check";
        items.add(new DashboardCandidateAnalysisResponse.RiskItem(key, label, value, level, value));
    }

    private List<DashboardCandidateAnalysisResponse.ChecklistAction> toChecklist(List<ChecklistItem> source) {
        if (source == null) {
            return List.of();
        }
        List<DashboardCandidateAnalysisResponse.ChecklistAction> items = new ArrayList<>();
        for (int index = 0; index < source.size(); index++) {
            ChecklistItem item = source.get(index);
            items.add(new DashboardCandidateAnalysisResponse.ChecklistAction(
                    "check-" + index,
                    item.getItem(),
                    item.getNote()
            ));
        }
        return items;
    }

    private String registeredSiteType(String assetType) {
        if ("BUILDING".equalsIgnoreCase(assetType) || "ROOF".equalsIgnoreCase(assetType)) {
            return "ROOF";
        }
        if ("PARKING_LOT".equalsIgnoreCase(assetType)) {
            return "PARKING_LOT";
        }
        return "LAND";
    }

    private boolean isPositive(String value) {
        return value.contains("가능") || value.contains("양호") || value.contains("낮음")
                || value.contains("적합") || value.contains("없음");
    }

    private Double number(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text) {
            try {
                return Double.valueOf(text);
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    private Integer integer(Object value, Integer fallback) {
        Double parsed = number(value);
        if (parsed == null) {
            return fallback;
        }
        return (int) Math.round(parsed);
    }

    private String text(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }
}
