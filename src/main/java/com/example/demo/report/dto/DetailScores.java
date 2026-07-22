package com.example.demo.report.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class DetailScores {

    @JsonProperty("ml_technical_score")
    private Integer mlTechnicalScore;

    @JsonProperty("ml_reason")
    private String mlReason;

    @JsonProperty("vision_ai_score")
    private Integer visionAiScore;

    @JsonProperty("vision_reason")
    private String visionReason;

    @JsonProperty("rule_based_score")
    private Integer ruleBasedScore;

    @JsonProperty("rule_reason")
    private String ruleReason;
}
