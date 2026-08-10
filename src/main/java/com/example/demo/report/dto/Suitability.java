package com.example.demo.report.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;


@Getter
@Setter
public class Suitability {
    @JsonProperty("rule_pass")
    private Boolean rulePass;

    @JsonProperty("rule_decision")
    private String ruleDecision;

    @JsonProperty("rule_message")
    private String ruleMessage;
}
