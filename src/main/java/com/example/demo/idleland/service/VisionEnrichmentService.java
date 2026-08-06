package com.example.demo.idleland.service;

import com.example.demo.idleland.client.MlScoringClient;
import com.example.demo.idleland.client.VWorldImageClient;
import com.example.demo.idleland.client.VisionAiClient;
import com.example.demo.idleland.entity.IdleLand;
import com.example.demo.report.dto.AiAnalysisResponse;
import com.example.demo.report.dto.DetailScores;
import com.example.demo.report.dto.SiteInfo;
import com.example.demo.report.dto.Simulation;
import com.example.demo.report.dto.VisionAiSimulation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// IdleLand 좌표 기준으로 VWorld 위성이미지 -> Vision AI -> ML(면적 가중 통합) 순으로 호출해
// AiAnalysisResponse의 부지 면적/점수/Vision 시뮬레이션 값을 실측 기반으로 보강한다.
// IdleLandReportService, DashboardCandidateAnalysisService, AnalysisSnapshotService가 공유해서 쓰던
// 거의 동일한 로직이 각자 따로 복붙돼 있던 걸 여기로 모았다.
// 성공하면 SHAP 기반 점수/추천·감점 이유는 그대로 두고 면적 가중 점수와 실제 탐지 이미지만 덮어쓴다.
// 실패해도 예외를 던지지 않고 조용히 원래 값을 유지한다.
@Slf4j
@Component
@RequiredArgsConstructor
public class VisionEnrichmentService {

    private final VWorldImageClient vWorldImageClient;
    private final VisionAiClient visionAiClient;
    private final MlScoringClient mlScoringClient;

    public void enrich(IdleLand idleLand, AiAnalysisResponse target) {
        if (idleLand.getLongitude() == null || idleLand.getLatitude() == null) {
            log.warn("유휴부지 id={} 위경도 정보가 없어 Vision 분석을 건너뜁니다.", idleLand.getId());
            return;
        }

        try {
            VWorldImageClient.VisionImageSource imageSource =
                    vWorldImageClient.fetchImage(idleLand.getLongitude(), idleLand.getLatitude());
            VisionAiClient.VisionPredictResponse visionResult =
                    visionAiClient.predict(imageSource.imageBytes(), imageSource.extent3857());

            // 탐지 결과가 없어도 위성이미지 자체는 보고서에 보여줄 가치가 있으므로 먼저 반영해둔다.
            target.setAnnotatedImageBase64(visionResult.getAnnotatedImage());

            if (visionResult.getPredictions() == null || visionResult.getPredictions().isEmpty()) {
                log.info("유휴부지 id={} Vision AI가 탐지한 후보가 없어 면적 가중 점수는 건너뜁니다.", idleLand.getId());
                return;
            }

            Map<String, Object> integrated = mlScoringClient.analyzeVisionJson(visionResult.getPredictions());
            Object resultsValue = integrated.get("results");
            if (resultsValue instanceof List<?> results && !results.isEmpty()
                    && results.get(0) instanceof Map<?, ?> result) {
                mergeVisionResult(target, result);
            } else {
                log.info("유휴부지 id={} ML 통합 분석 결과가 비어 있어 면적 가중 점수는 건너뜁니다.", idleLand.getId());
            }
        } catch (Exception exception) {
            log.warn("유휴부지 id={} Vision/ML 통합 분석 실패, 기존 값을 유지합니다: {}",
                    idleLand.getId(), exception.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void mergeVisionResult(AiAnalysisResponse target, Map<?, ?> result) {
        Object siteInfoObj = result.get("1_site_info");
        SiteInfo siteInfo = target.getSiteInfo();
        if (siteInfo != null && siteInfoObj instanceof Map<?, ?> site) {
            siteInfo.setTotalArea(number(site.get("total_area_m2"), siteInfo.getTotalArea()));
            siteInfo.setAvailableArea(number(site.get("available_area_m2"), siteInfo.getAvailableArea()));
            siteInfo.setAvailabilityRatePercent(number(
                    site.get("availability_rate_percent"), siteInfo.getAvailabilityRatePercent()
            ));
        }

        if (target.getScoresAndEvaluation() != null && result.get("2_scores_and_evaluation") instanceof Map<?, ?> scores) {
            target.getScoresAndEvaluation().setTotalScore(integer(
                    scores.get("total_score"), target.getScoresAndEvaluation().getTotalScore()
            ));
            Object grade = scores.get("grade");
            if (grade != null) {
                target.getScoresAndEvaluation().setGrade(String.valueOf(grade));
            }

            DetailScores detailScores = target.getScoresAndEvaluation().getDetailScores();
            if (detailScores != null && scores.get("detail_scores") instanceof Map<?, ?> detail) {
                mergeVisionDetailScore(detailScores, detail, siteInfoObj);
                detailScores.setRuleBasedScore(integer(detail.get("rule_based_score"), detailScores.getRuleBasedScore()));
            }

            if (detailScores != null && scores.get("suitability") instanceof Map<?, ?> suitability
                    && suitability.get("rule_message") instanceof String ruleMessage && !ruleMessage.isBlank()) {
                detailScores.setRuleReason(ruleMessage);
            }
        }

        if (result.get("3_vision_ai_and_simulation") instanceof Map<?, ?> visionSection
                && visionSection.get("vision_analysis") instanceof Map<?, ?> rawVisionAnalysis) {
            VisionAiSimulation visionAiSimulation = target.getVisionAiSimulation();
            if (visionAiSimulation == null) {
                visionAiSimulation = new VisionAiSimulation();
                target.setVisionAiSimulation(visionAiSimulation);
            }

            Map<String, Object> visionAnalysis = new LinkedHashMap<>((Map<String, Object>) rawVisionAnalysis);
            // 통합 파이프라인은 방위를 aspect_direction_degree(각도)로 주는데, PDF 라벨은 aspect_direction 키를 찾는다.
            if (visionAnalysis.get("aspect_direction_degree") != null) {
                visionAnalysis.put("aspect_direction", visionAnalysis.get("aspect_direction_degree"));
            }
            if (visionAnalysis.get("candidate_type") != null) {
                visionAnalysis.put("vision_candidate_type", visionAnalysis.get("candidate_type"));
            }
            if (visionAnalysis.get("confidence") instanceof Number confidenceNumber) {
                visionAnalysis.put("vision_confidence_percent",
                        Math.round(confidenceNumber.doubleValue() * 1000) / 10.0);
            }
            visionAiSimulation.setVisionAnalysis(visionAnalysis);

            if (visionSection.get("simulation") instanceof Map<?, ?> simulationMap) {
                Simulation simulation = visionAiSimulation.getSimulation();
                if (simulation == null) {
                    simulation = new Simulation();
                    visionAiSimulation.setSimulation(simulation);
                }
                simulation.setRecommendedCapacityKw(integer(
                        simulationMap.get("recommended_capacity_kw"), simulation.getRecommendedCapacityKw()
                ));
                simulation.setAnnualGenerationKwh(longNumber(
                        simulationMap.get("annual_generation_kwh"), simulation.getAnnualGenerationKwh()
                ));
                simulation.setAnnualRevenueKrw(longNumber(
                        simulationMap.get("annual_revenue_krw"), simulation.getAnnualRevenueKrw()
                ));
            }
        }
    }

    // ML 통합 분석의 detail_scores(vision_area_score/vision_confidence)를
    // 보고서의 "Vision AI 환경 평가" 점수·근거 텍스트로 옮긴다.
    private void mergeVisionDetailScore(DetailScores detailScores, Map<?, ?> detail, Object siteInfoObj) {
        if (detail.get("vision_area_score") instanceof Number number) {
            detailScores.setVisionAiScore((int) Math.round(number.doubleValue() * 100));
        }

        if (detail.get("vision_confidence") instanceof Number number) {
            double confidencePercent = number.doubleValue() * 100;
            Object availableArea = siteInfoObj instanceof Map<?, ?> site ? site.get("available_area_m2") : null;
            String areaText = availableArea instanceof Number areaNumber
                    ? String.format("%.1f㎡", areaNumber.doubleValue())
                    : "면적 정보 없음";
            detailScores.setVisionReason(String.format(
                    "Vision AI가 탐지 신뢰도 %.1f%%, 실제 탐지 면적 %s를 기준으로 산출한 면적 가중 점수입니다.",
                    confidencePercent, areaText));
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
}
