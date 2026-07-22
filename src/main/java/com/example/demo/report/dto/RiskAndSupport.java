package com.example.demo.report.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class RiskAndSupport {

    @JsonProperty("rule_based_risk_check")
    private RiskCheck ruleBasedRiskCheck;

    @JsonProperty("recommended_subsidies")
    private List<String> recommendedSubsidies;
}
