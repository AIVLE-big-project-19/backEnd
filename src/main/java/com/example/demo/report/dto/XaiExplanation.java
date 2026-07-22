package com.example.demo.report.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class XaiExplanation {

    @JsonProperty("bonus_reason")
    private List<String> bonusReason;

    @JsonProperty("penalty_reason")
    private List<String> penaltyReason;
}
