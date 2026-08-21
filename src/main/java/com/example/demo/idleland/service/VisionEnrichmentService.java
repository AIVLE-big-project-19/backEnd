package com.example.demo.idleland.service;

import com.example.demo.idleland.client.MlScoringClient;
import com.example.demo.idleland.client.PolicyAgentClient;
import com.example.demo.idleland.client.VWorldImageClient;
import com.example.demo.idleland.client.VisionAiClient;
import com.example.demo.idleland.entity.IdleLand;
import com.example.demo.idleland.repository.IdleLandRepository;
import com.example.demo.idleland.support.PercentileCalculator;
import com.example.demo.report.dto.AgentExplanation;
import com.example.demo.report.dto.AiAnalysisResponse;
import com.example.demo.report.dto.BusinessRoute;
import com.example.demo.report.dto.DetailScores;
import com.example.demo.report.dto.RecommendedSubsidy;
import com.example.demo.report.dto.RegulatoryAssessment;
import com.example.demo.report.dto.RiskAndSupport;
import com.example.demo.report.dto.RiskCheck;
import com.example.demo.report.dto.SiteInfo;
import com.example.demo.report.dto.Simulation;
import com.example.demo.report.dto.Suitability;
import com.example.demo.report.dto.VisionAiSimulation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class VisionEnrichmentService {

    private final VWorldImageClient vWorldImageClient;
    private final VisionAiClient visionAiClient;
    private final MlScoringClient mlScoringClient;
    private final PolicyAgentClient policyAgentClient;
    private final IdleLandRepository idleLandRepository;

    public void enrich(IdleLand idleLand, AiAnalysisResponse target) {
        Double freshVisionScore = enrichFromVision(idleLand, target);
        recalculateScores(idleLand, target, freshVisionScore);
        recalculateGrade(idleLand, target);
        enrichFromPolicyAgent(idleLand, target);
    }

    private void recalculateScores(IdleLand idleLand, AiAnalysisResponse target, Double freshVisionScore) {
        try {
            var evaluation = target.getScoresAndEvaluation();
            DetailScores detailScores = evaluation == null ? null : evaluation.getDetailScores();
            Double storedTotal = idleLand.getSolarReadinessScore();
            if (evaluation == null || detailScores == null || storedTotal == null) {
                return;
            }

            Double storedVision = idleLand.getVisionScore();
            double mlTechnical = storedVision == null ? storedTotal : (2 * storedTotal - storedVision);
            detailScores.setMlTechnicalScore((int) Math.round(mlTechnical));

            Double effectiveVisionScore = freshVisionScore != null ? freshVisionScore : storedVision;
            double newTotal = effectiveVisionScore == null ? mlTechnical : (mlTechnical + effectiveVisionScore) / 2;
            evaluation.setTotalScore((int) Math.round(newTotal));
        } catch (Exception exception) {
            log.warn("유휴부지 id={} 점수 재계산 실패, 라이브 ML 값을 유지합니다: {}", idleLand.getId(), exception.getMessage());
        }
    }


    private void recalculateGrade(IdleLand idleLand, AiAnalysisResponse target) {
        try {
            var evaluation = target.getScoresAndEvaluation();
            if (evaluation == null || evaluation.getTotalScore() == null) {
                return;
            }

            List<Double> population = idleLandRepository.findSolarReadinessScoresByAssetTypeNorm(idleLand.getAssetTypeNorm());
            if (population == null || population.isEmpty()) {
                return;
            }

            double totalScore = evaluation.getTotalScore();
            List<Double> withCandidate = new ArrayList<>(population);
            withCandidate.add(totalScore);

            Double percentile = PercentileCalculator.percentile(withCandidate, totalScore);
            if (percentile == null) {
                return;
            }

            evaluation.setGrade(PercentileCalculator.gradeFromPercentile(percentile));
        } catch (Exception exception) {
            log.warn("유휴부지 id={} 등급 재계산 실패, 기존 값을 유지합니다: {}", idleLand.getId(), exception.getMessage());
        }
    }


    private Double enrichFromVision(IdleLand idleLand, AiAnalysisResponse target) {
        if (idleLand.getLongitude() == null || idleLand.getLatitude() == null) {
            log.warn("유휴부지 id={} 위경도 정보가 없어 Vision 분석을 건너뜁니다.", idleLand.getId());
            return null;
        }

        try {
            VWorldImageClient.VisionImageSource imageSource =
                    vWorldImageClient.fetchImage(idleLand.getLongitude(), idleLand.getLatitude());
            VisionAiClient.VisionPredictResponse visionResult =
                    visionAiClient.predict(imageSource.imageBytes(), imageSource.extent3857());
            target.setFinalVisualizationImageBase64(visionResult.getFinalVisualizationImage());

            if (visionResult.getPredictions() == null || visionResult.getPredictions().isEmpty()) {
                log.info("유휴부지 id={} Vision AI가 탐지한 후보가 없어 면적 가중 점수는 건너뜁니다.", idleLand.getId());
                return null;
            }


            Integer estimatedPanelCount = visionResult.getPredictions().get(0).get("estimated_panel_count") instanceof Number number
                    ? number.intValue() : null;
            List<Integer> population = idleLandRepository.findEstimatedPanelCountsByAssetTypeNorm(idleLand.getAssetTypeNorm());
            Double percentile = PercentileCalculator.percentile(population, estimatedPanelCount);
            Double visionScore = percentile == null ? null : percentile * 100;

            Map<String, Object> integrated = mlScoringClient.analyzeVisionJson(visionResult.getPredictions());
            Object resultsValue = integrated.get("results");
            if (resultsValue instanceof List<?> results && !results.isEmpty()
                    && results.get(0) instanceof Map<?, ?> result) {
                mergeVisionResult(target, result, visionScore);
            } else {
                log.info("유휴부지 id={} ML 통합 분석 결과가 비어 있어 면적 가중 점수는 건너뜁니다.", idleLand.getId());
            }
            return visionScore;
        } catch (Exception exception) {
            log.warn("유휴부지 id={} Vision/ML 통합 분석 실패, 기존 값을 유지합니다: {}",
                    idleLand.getId(), exception.getMessage());
            return null;
        }
    }


    @SuppressWarnings("unchecked")
    private void enrichFromPolicyAgent(IdleLand idleLand, AiAnalysisResponse target) {
        RiskAndSupport riskAndSupport = target.getRiskAndSupport();
        if (riskAndSupport == null) {
            riskAndSupport = new RiskAndSupport();
            target.setRiskAndSupport(riskAndSupport);
        }

        try {
            Map<String, Object> response = policyAgentClient.analyze(target);
            if (!(response.get("result") instanceof Map<?, ?> result)
                    || !(result.get("4_risk_and_support") instanceof Map<?, ?> risk)) {
                log.info("유휴부지 id={} 정책 Agent 응답에 4_risk_and_support가 없어 건너뜁니다.", idleLand.getId());
                return;
            }
            mergePolicyAgentResult(riskAndSupport, risk);
        } catch (Exception exception) {
            log.warn("유휴부지 id={} 정책 Agent 호출 실패, 기존 값을 유지합니다: {}",
                    idleLand.getId(), exception.getMessage());
        }
    }

    private void mergePolicyAgentResult(RiskAndSupport riskAndSupport, Map<?, ?> risk) {
        if (risk.get("regulatory_assessment") instanceof Map<?, ?> assessmentMap) {
            RegulatoryAssessment assessment = new RegulatoryAssessment();
            assessment.setFinalDecision(text(assessmentMap.get("final_decision")));
            assessment.setFinalReason(text(assessmentMap.get("final_reason")));
            assessment.setSetbackViolation(assessmentMap.get("setback_violation") instanceof Boolean bool ? bool : null);
            assessment.setDataGaps(stringList(assessmentMap.get("data_gaps")));
            riskAndSupport.setRegulatoryAssessment(assessment);
        }

        if (risk.get("business_route") instanceof Map<?, ?> routeMap) {
            BusinessRoute route = new BusinessRoute();
            route.setRouteType(text(routeMap.get("route_type")));
            route.setReason(text(routeMap.get("reason")));
            riskAndSupport.setBusinessRoute(route);
        }

        if (risk.get("recommended_subsidies") instanceof List<?> programs) {
            List<RecommendedSubsidy> recommendedPrograms = new ArrayList<>();
            for (Object item : programs) {
                if (item instanceof Map<?, ?> programMap) {
                    recommendedPrograms.add(toRecommendedSubsidy(programMap));
                }
            }
            riskAndSupport.setRecommendedPrograms(recommendedPrograms);
        }

        if (risk.get("agent_explanation") instanceof Map<?, ?> explanationMap) {
            AgentExplanation explanation = new AgentExplanation();
            explanation.setCaution(text(explanationMap.get("caution")));
            riskAndSupport.setAgentExplanation(explanation);
        }
    }

    private RecommendedSubsidy toRecommendedSubsidy(Map<?, ?> programMap) {
        RecommendedSubsidy subsidy = new RecommendedSubsidy();
        subsidy.setProgramName(text(programMap.get("program_name")));
        subsidy.setPriority(programMap.get("priority") instanceof Number number ? number.intValue() : null);
        subsidy.setStatus(text(programMap.get("status")));
        subsidy.setSummary(text(programMap.get("summary")));
        subsidy.setSourceUrl(text(programMap.get("source_url")));
        subsidy.setMatchStatus(text(programMap.get("match_status")));
        subsidy.setSupportType(text(programMap.get("support_type")));
        subsidy.setSupportSummary(text(programMap.get("support_summary")));
        subsidy.setRepaymentSummary(text(programMap.get("repayment_summary")));
        subsidy.setApplicationPeriod(text(programMap.get("application_period")));
        subsidy.setReason(text(programMap.get("reason")));
        subsidy.setRequiredChecks(stringList(programMap.get("required_checks")));
        return subsidy;
    }

    private String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private List<String> stringList(Object value) {
        if (!(value instanceof List<?> list)) {
            return null;
        }
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (item != null) {
                result.add(String.valueOf(item));
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private void mergeVisionResult(AiAnalysisResponse target, Map<?, ?> result, Double visionScore) {
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
                mergeVisionDetailScore(detailScores, detail, siteInfoObj, visionScore);
                detailScores.setRuleBasedScore(integer(detail.get("rule_based_score"), detailScores.getRuleBasedScore()));
            }

            if (scores.get("suitability") instanceof Map<?, ?> suitabilityMap) {
                if (detailScores != null
                        && suitabilityMap.get("rule_message") instanceof String ruleMessage && !ruleMessage.isBlank()) {
                    detailScores.setRuleReason(ruleMessage);
                }


                Suitability suitability = new Suitability();
                suitability.setRulePass(suitabilityMap.get("rule_pass") instanceof Boolean bool ? bool : null);
                suitability.setRuleDecision(text(suitabilityMap.get("rule_decision")));
                suitability.setRuleMessage(text(suitabilityMap.get("rule_message")));
                target.getScoresAndEvaluation().setSuitability(suitability);
            }
        }

        if (result.get("3_vision_ai_simulation") instanceof Map<?, ?> visionSection
                && visionSection.get("vision_analysis") instanceof Map<?, ?> rawVisionAnalysis) {
            VisionAiSimulation visionAiSimulation = target.getVisionAiSimulation();
            if (visionAiSimulation == null) {
                visionAiSimulation = new VisionAiSimulation();
                target.setVisionAiSimulation(visionAiSimulation);
            }

            Map<String, Object> visionAnalysis = new LinkedHashMap<>((Map<String, Object>) rawVisionAnalysis);
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

        if (target.getRiskAndSupport() != null
                && result.get("4_risk_and_support") instanceof Map<?, ?> risk
                && risk.get("rule_based_risk_check") instanceof Map<?, ?> riskCheckMap) {
            RiskCheck riskCheck = target.getRiskAndSupport().getRuleBasedRiskCheck();
            if (riskCheck != null && riskCheckMap.get("regulation") instanceof String regulation && !regulation.isBlank()) {
                riskCheck.setRegulation(regulation);
            }
        }
    }


    private void mergeVisionDetailScore(DetailScores detailScores, Map<?, ?> detail, Object siteInfoObj, Double visionScore) {
        if (visionScore != null) {
            detailScores.setVisionAiScore((int) Math.round(visionScore));
        }

        if (detail.get("vision_confidence") instanceof Number number) {
            double confidencePercent = number.doubleValue() * 100;
            Object availableArea = siteInfoObj instanceof Map<?, ?> site ? site.get("available_area_m2") : null;
            String areaText = availableArea instanceof Number areaNumber
                    ? String.format("%.1f㎡", areaNumber.doubleValue())
                    : "면적 정보 없음";
            detailScores.setVisionReason(String.format(
                    "Vision AI가 탐지 신뢰도 %.1f%%, 실제 탐지 면적 %s를 기준으로 추정한 설치 가능 패널 개수 기반 적합도 점수입니다.",
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
