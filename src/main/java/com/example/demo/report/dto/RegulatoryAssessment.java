package com.example.demo.report.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;


@Getter
@Setter
public class RegulatoryAssessment {
    private String finalDecision;
    private String finalReason;
    private Boolean setbackViolation;
    private List<String> dataGaps;
}
