package com.example.demo.report.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class AiAnalysisResponse {

    @JsonProperty("target_type")
    private String targetType; // ROOF / LAND

    @JsonProperty("1_site_info")
    private SiteInfo siteInfo;

    @JsonProperty("2_scores_and_evaluation")
    private ScoresAndEvaluation scoresAndEvaluation;

    @JsonProperty("3_vision_ai_simulation")
    private VisionAiSimulation visionAiSimulation;

    @JsonProperty("4_risk_and_support")
    private RiskAndSupport riskAndSupport;

    @JsonProperty("5_pre_investigation_checklist")
    private List<ChecklistItem> preInvestigationChecklist;
}
