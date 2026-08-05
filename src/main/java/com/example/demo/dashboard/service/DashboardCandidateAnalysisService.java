package com.example.demo.dashboard.service;

import com.example.demo.dashboard.client.PvgisClient;
import com.example.demo.dashboard.dto.DashboardCandidateAnalysisResponse;
import com.example.demo.analysis.service.AnalysisSnapshotService;
import com.example.demo.global.exception.CustomException;
import com.example.demo.global.exception.ErrorCode;
import com.example.demo.idleland.client.MlScoringClient;
import com.example.demo.idleland.client.VWorldImageClient;
import com.example.demo.idleland.client.VisionAiClient;
import com.example.demo.idleland.dto.MlRankResponse;
import com.example.demo.idleland.entity.IdleLand;
import com.example.demo.idleland.repository.IdleLandRepository;
import com.example.demo.report.dto.AiAnalysisResponse;
import com.example.demo.report.dto.ChecklistItem;
import com.example.demo.report.dto.DetailScores;
import com.example.demo.report.dto.RiskCheck;
import com.example.demo.report.dto.ScoresAndEvaluation;
import com.example.demo.report.dto.Simulation;
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
    private static final long INSTALLATION_COST_PER_KW = 1_300_000L;
    private static final long REVENUE_PER_KWH = 160L;
    private static final double DEFAULT_TILT_DEGREES = 30d;
    private static final double[] MONTHLY_FALLBACK_WEIGHTS = {
            0.62d, 0.70d, 0.88d, 1.02d, 1.14d, 1.20d,
            1.18d, 1.08d, 0.94d, 0.82d, 0.68d, 0.60d
    };

    private final IdleLandRepository idleLandRepository;
    private final MlScoringClient mlScoringClient;
    private final VWorldImageClient vWorldImageClient;
    private final VisionAiClient visionAiClient;
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

        enrichWithVisionAnalysis(idleLand, analysis);
        DashboardCandidateAnalysisResponse dashboardResponse = toResponse(idleLand, analysis);
        Long analysisId = analysisSnapshotService.save(userId, idleLand, analysis, dashboardResponse);
        return new DashboardCandidateAnalysisResponse(
                dashboardResponse.id(), dashboardResponse.sourceId(), dashboardResponse.address(), dashboardResponse.siteType(),
                dashboardResponse.latitude(), dashboardResponse.longitude(), dashboardResponse.areaM2(), dashboardResponse.usableRoofAreaM2(),
                dashboardResponse.roofUtilizationRate(), dashboardResponse.suitabilityScore(), dashboardResponse.grade(),
                dashboardResponse.priorityRank(), dashboardResponse.capacityKw(), dashboardResponse.capacityEstimate(),
                dashboardResponse.annualGenerationKwh(), dashboardResponse.estimatedAnnualRevenue(), dashboardResponse.roiPercent(),
                dashboardResponse.paybackPeriodYears(), dashboardResponse.generationForecast(), dashboardResponse.scores(),
                dashboardResponse.roofAnalysis(), dashboardResponse.risks(), dashboardResponse.checklist(), analysisId
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
        EconomicEstimate economics = resolveEconomics(
                capacityKw,
                pvgisForecast.map(PvgisClient.Forecast::annualGenerationKwh).orElse(null)
        );
        DashboardCandidateAnalysisResponse.GenerationForecast generationForecast = pvgisForecast
                .map(this::toGenerationForecast)
                .orElseGet(() -> fallbackGenerationForecast(economics, roofAnalysis));

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
                economics.annualGenerationKwh(),
                economics.annualRevenueKrw(),
                economics.roiPercent(),
                economics.paybackYears(),
                generationForecast,
                new DashboardCandidateAnalysisResponse.ScoreBreakdown(
                        details == null ? null : details.getMlTechnicalScore(),
                        details == null ? null : details.getVisionAiScore(),
                        details == null ? null : details.getRuleBasedScore()
                ),
                roofAnalysis,
                toRisks(analysis),
                toChecklist(analysis.getPreInvestigationChecklist()),
                null
        );
    }

    private void enrichWithVisionAnalysis(IdleLand idleLand, AiAnalysisResponse target) {
        if (idleLand.getLongitude() == null || idleLand.getLatitude() == null) {
            return;
        }

        try {
            VWorldImageClient.VisionImageSource imageSource =
                    vWorldImageClient.fetchImage(idleLand.getLongitude(), idleLand.getLatitude());
            VisionAiClient.VisionPredictResponse visionResult =
                    visionAiClient.predict(imageSource.imageBytes(), imageSource.extent3857());
            if (visionResult.getPredictions() == null || visionResult.getPredictions().isEmpty()) {
                return;
            }

            Map<String, Object> integrated = mlScoringClient.analyzeVisionJson(visionResult.getPredictions());
            Object resultsValue = integrated.get("results");
            if (resultsValue instanceof List<?> results && !results.isEmpty()
                    && results.get(0) instanceof Map<?, ?> result) {
                mergeVisionResult(target, result);
            }
        } catch (Exception exception) {
            log.warn("후보지 id={} Vision 분석 보강 실패: {}", idleLand.getId(), exception.getMessage());
        }
    }

    private void mergeVisionResult(AiAnalysisResponse target, Map<?, ?> result) {
        SiteInfo site = target.getSiteInfo();
        if (site != null && result.get("1_site_info") instanceof Map<?, ?> siteValues) {
            site.setTotalArea(number(siteValues.get("total_area_m2"), site.getTotalArea()));
            site.setAvailableArea(number(siteValues.get("available_area_m2"), site.getAvailableArea()));
            site.setAvailabilityRatePercent(number(
                    siteValues.get("availability_rate_percent"),
                    site.getAvailabilityRatePercent()
            ));
        }

        if (!(result.get("3_vision_ai_and_simulation") instanceof Map<?, ?> visionValues)) {
            return;
        }
        VisionAiSimulation vision = target.getVisionAiSimulation();
        if (vision == null) {
            vision = new VisionAiSimulation();
            target.setVisionAiSimulation(vision);
        }
        if (visionValues.get("vision_analysis") instanceof Map<?, ?> rawAnalysis) {
            Map<String, Object> analysisValues = new java.util.LinkedHashMap<>();
            rawAnalysis.forEach((key, value) -> analysisValues.put(String.valueOf(key), value));
            vision.setVisionAnalysis(analysisValues);
        }
        if (visionValues.get("simulation") instanceof Map<?, ?> simulationValues) {
            Simulation simulation = vision.getSimulation();
            if (simulation == null) {
                simulation = new Simulation();
                vision.setSimulation(simulation);
            }
            simulation.setRecommendedCapacityKw(integer(
                    simulationValues.get("recommended_capacity_kw"),
                    simulation.getRecommendedCapacityKw()
            ));
            simulation.setAnnualGenerationKwh(longNumber(
                    simulationValues.get("annual_generation_kwh"),
                    simulation.getAnnualGenerationKwh()
            ));
            simulation.setAnnualRevenueKrw(longNumber(
                    simulationValues.get("annual_revenue_krw"),
                    simulation.getAnnualRevenueKrw()
            ));
        }
    }


    private Double number(Object value, Double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return fallback;
    }

    private Integer integer(Object value, Integer fallback) {
        if (value instanceof Number number) {
            return (int) Math.round(number.doubleValue());
        }
        return fallback;
    }

    private Long longNumber(Object value, Long fallback) {
        if (value instanceof Number number) {
            return Math.round(number.doubleValue());
        }
        return fallback;
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
            Integer capacityKw,
            Long locationBasedAnnualGenerationKwh
    ) {
        Long annualGenerationKwh = locationBasedAnnualGenerationKwh;
        Long annualRevenueKrw = null;
        Double roiPercent = null;
        Double paybackYears = null;

        if (annualGenerationKwh == null && capacityKw != null) {
            annualGenerationKwh = capacityKw * GENERATION_PER_KW;
        }
        if (annualRevenueKrw == null && annualGenerationKwh != null) {
            annualRevenueKrw = annualGenerationKwh * REVENUE_PER_KWH;
        }

        if (capacityKw != null && annualRevenueKrw != null && annualRevenueKrw > 0) {
            long installationCost = capacityKw * INSTALLATION_COST_PER_KW;
            if (roiPercent == null) {
                roiPercent = roundOneDecimal(annualRevenueKrw / (double) installationCost * 100d);
            }
            if (paybackYears == null) {
                paybackYears = roundOneDecimal(installationCost / (double) annualRevenueKrw);
            }
        }

        return new EconomicEstimate(
                capacityKw,
                annualGenerationKwh,
                annualRevenueKrw,
                roiPercent,
                paybackYears
        );
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
            DashboardCandidateAnalysisResponse.RoofAnalysis roofAnalysis
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

        return new DashboardCandidateAnalysisResponse.GenerationForecast(
                "내부 계절 가중치",
                "SEASONAL_WEIGHT_FALLBACK",
                economics.capacityKw(),
                roofAnalysis.installAngleDegrees() == null ? DEFAULT_TILT_DEGREES : roofAnalysis.installAngleDegrees(),
                toPvgisAzimuth(roofAnalysis.moduleDirection()),
                null,
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
            Double paybackYears
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
                text(values.get("recommended_orientation"), null),
                number(values.get("recommended_tilt_angle_deg"))
        );
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
        return "BUILDING".equalsIgnoreCase(assetType) ? "ROOF" : "LAND";
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

    private String text(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }
}
