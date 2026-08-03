package com.example.demo.idleland.service;

import com.example.demo.global.exception.CustomException;
import com.example.demo.global.exception.ErrorCode;
import com.example.demo.idleland.client.MlScoringClient;
import com.example.demo.idleland.client.VWorldImageClient;
import com.example.demo.idleland.client.VisionAiClient;
import com.example.demo.idleland.dto.MlRankResponse;
import com.example.demo.idleland.entity.IdleLand;
import com.example.demo.idleland.repository.IdleLandRepository;
import com.example.demo.report.dto.AiAnalysisResponse;
import com.example.demo.report.dto.DetailScores;
import com.example.demo.report.dto.Simulation;
import com.example.demo.report.dto.SiteInfo;
import com.example.demo.report.dto.VisionAiSimulation;
import com.example.demo.report.service.ReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// 검색으로 찾은 유휴부지 후보 1건에 대해 ML을 다시 호출해(SHAP 포함) 기존 PDF 보고서를 생성한다.
// 추가로 VWorld 위성이미지 -> Vision AI -> ML(면적 가중 통합점수) 순으로 호출해,
// 성공하면 SHAP 기반 점수/추천·감점 이유는 그대로 두고 면적 가중 점수와 실제 탐지 이미지만 덮어쓴다.
// 이 보강 단계가 실패해도 보고서 다운로드 자체는 기존 방식(면적 미반영)대로 계속 진행된다.
@Slf4j
@Service
@RequiredArgsConstructor
public class IdleLandReportService {

    private final IdleLandRepository idleLandRepository;
    private final MlScoringClient mlScoringClient;
    private final VWorldImageClient vWorldImageClient;
    private final VisionAiClient visionAiClient;
    private final ReportService reportService;

    public byte[] generateReportPdf(Long idleLandId) throws IOException {
        IdleLand idleLand = idleLandRepository.findById(idleLandId)
                .orElseThrow(() -> new CustomException(ErrorCode.IDLE_LAND_NOT_FOUND));

        String datasetType = "BUILDING".equals(idleLand.getAssetTypeNorm()) ? "building" : "land";

        MlRankResponse response = mlScoringClient.rank(datasetType, List.of(idleLand), 1, true);
        List<AiAnalysisResponse> topCandidates = response.getTopCandidates();
        if (topCandidates == null || topCandidates.isEmpty()) {
            throw new CustomException(ErrorCode.ML_SERVER_REQUEST_FAILED, "ML 서버가 분석 결과를 반환하지 않았습니다.");
        }
        AiAnalysisResponse baseResult = topCandidates.get(0);

        enrichWithVisionAnalysis(idleLand, baseResult);

        return reportService.generateReportPdf(baseResult);
    }

    private void enrichWithVisionAnalysis(IdleLand idleLand, AiAnalysisResponse target) {
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
            Object resultsObj = integrated.get("results");
            if (!(resultsObj instanceof List<?> results) || results.isEmpty()
                    || !(results.get(0) instanceof Map<?, ?> firstResult)) {
                log.info("유휴부지 id={} ML 통합 분석 결과가 비어 있어 면적 가중 점수는 건너뜁니다.", idleLand.getId());
                return;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> typedFirstResult = (Map<String, Object>) firstResult;
            mergeIntegratedScore(target, typedFirstResult);
        } catch (Exception e) {
            log.warn("유휴부지 id={} Vision/ML 통합 분석 실패, 기존 방식으로 보고서를 생성합니다: {}",
                    idleLand.getId(), e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private void mergeIntegratedScore(AiAnalysisResponse target, Map<String, Object> integratedResult) {
        Object siteInfoObj = integratedResult.get("1_site_info");
        SiteInfo siteInfo = target.getSiteInfo();
        if (siteInfo != null && siteInfoObj instanceof Map<?, ?> site) {
            if (site.get("total_area_m2") instanceof Number number) {
                siteInfo.setTotalArea(number.doubleValue());
            }
            if (site.get("available_area_m2") instanceof Number number) {
                siteInfo.setAvailableArea(number.doubleValue());
            }
            if (site.get("availability_rate_percent") instanceof Number number) {
                siteInfo.setAvailabilityRatePercent(number.doubleValue());
            }
        }

        Object scoresObj = integratedResult.get("2_scores_and_evaluation");
        if (target.getScoresAndEvaluation() != null && scoresObj instanceof Map<?, ?> scores) {
            Object totalScore = scores.get("total_score");
            if (totalScore instanceof Number number) {
                target.getScoresAndEvaluation().setTotalScore((int) Math.round(number.doubleValue()));
            }
            Object grade = scores.get("grade");
            if (grade != null) {
                target.getScoresAndEvaluation().setGrade(String.valueOf(grade));
            }

            DetailScores detailScores = target.getScoresAndEvaluation().getDetailScores();
            if (detailScores != null && scores.get("detail_scores") instanceof Map<?, ?> detail) {
                mergeVisionDetailScore(detailScores, detail, siteInfoObj);

                if (detail.get("rule_based_score") instanceof Number number) {
                    detailScores.setRuleBasedScore((int) Math.round(number.doubleValue()));
                }
            }

            if (detailScores != null && scores.get("suitability") instanceof Map<?, ?> suitability
                    && suitability.get("rule_message") instanceof String ruleMessage && !ruleMessage.isBlank()) {
                detailScores.setRuleReason(ruleMessage);
            }
        }

        Object visionSectionObj = integratedResult.get("3_vision_ai_and_simulation");
        if (visionSectionObj instanceof Map<?, ?> visionSection
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
                if (simulationMap.get("recommended_capacity_kw") instanceof Number number) {
                    simulation.setRecommendedCapacityKw((int) Math.round(number.doubleValue()));
                }
                if (simulationMap.get("annual_generation_kwh") instanceof Number number) {
                    simulation.setAnnualGenerationKwh(Math.round(number.doubleValue()));
                }
                if (simulationMap.get("annual_revenue_krw") instanceof Number number) {
                    simulation.setAnnualRevenueKrw(Math.round(number.doubleValue()));
                }
            }
        }
    }

    // ML 통합 분석의 detail_scores(vision_area_score/vision_confidence)를
    // 보고서의 "Vision AI 환경 평가" 점수·근거 텍스트로 옮긴다.
    private void mergeVisionDetailScore(DetailScores detailScores, Map<?, ?> detail, Object siteInfoObj) {
        Object visionAreaScore = detail.get("vision_area_score");
        if (visionAreaScore instanceof Number number) {
            detailScores.setVisionAiScore((int) Math.round(number.doubleValue() * 100));
        }

        Object visionConfidence = detail.get("vision_confidence");
        if (visionConfidence instanceof Number number) {
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
}
