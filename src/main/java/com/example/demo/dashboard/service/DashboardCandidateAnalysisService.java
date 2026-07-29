package com.example.demo.dashboard.service;

import com.example.demo.dashboard.dto.DashboardCandidateAnalysisResponse;
import com.example.demo.global.exception.CustomException;
import com.example.demo.global.exception.ErrorCode;
import com.example.demo.idleland.client.MlScoringClient;
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
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class DashboardCandidateAnalysisService {

    private final IdleLandRepository idleLandRepository;
    private final MlScoringClient mlScoringClient;

    public DashboardCandidateAnalysisResponse analyze(Long idleLandId) {
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

        return toResponse(idleLand, analysis);
    }

    private DashboardCandidateAnalysisResponse toResponse(IdleLand idleLand, AiAnalysisResponse analysis) {
        SiteInfo site = analysis.getSiteInfo();
        ScoresAndEvaluation evaluation = analysis.getScoresAndEvaluation();
        DetailScores details = evaluation == null ? null : evaluation.getDetailScores();
        VisionAiSimulation vision = analysis.getVisionAiSimulation();
        Simulation simulation = vision == null ? null : vision.getSimulation();
        Map<String, Object> visionValues = vision == null || vision.getVisionAnalysis() == null
                ? Map.of()
                : vision.getVisionAnalysis();

        return new DashboardCandidateAnalysisResponse(
                idleLand.getId(),
                idleLand.getSourceId(),
                site != null && site.getAddress() != null ? site.getAddress() : idleLand.getAddress(),
                normalizeSiteType(analysis.getTargetType(), idleLand.getAssetTypeNorm()),
                idleLand.getLatitude(),
                idleLand.getLongitude(),
                site == null ? null : site.getTotalArea(),
                site == null ? null : site.getAvailableArea(),
                site == null ? null : site.getAvailabilityRatePercent(),
                evaluation == null ? null : evaluation.getTotalScore(),
                evaluation == null ? null : evaluation.getGrade(),
                evaluation == null ? null : evaluation.getPriorityRank(),
                simulation == null ? null : simulation.getRecommendedCapacityKw(),
                simulation == null ? null : simulation.getAnnualGenerationKwh(),
                simulation == null ? null : simulation.getAnnualRevenueKrw(),
                simulation == null ? null : simulation.getRoiPercent(),
                simulation == null ? null : simulation.getPaybackYears(),
                new DashboardCandidateAnalysisResponse.ScoreBreakdown(
                        details == null ? null : details.getMlTechnicalScore(),
                        details == null ? null : details.getVisionAiScore(),
                        details == null ? null : details.getRuleBasedScore()
                ),
                toRoofAnalysis(visionValues, analysis.getTargetType(), site),
                toRisks(analysis),
                toChecklist(analysis.getPreInvestigationChecklist())
        );
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

    private String normalizeSiteType(String targetType, String assetType) {
        if ("ROOF".equalsIgnoreCase(targetType) || "BUILDING".equalsIgnoreCase(targetType)) {
            return "ROOF";
        }
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
