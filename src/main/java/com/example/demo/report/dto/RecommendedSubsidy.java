package com.example.demo.report.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;


@Getter
@Setter
public class RecommendedSubsidy {
    private String programName;
    private Integer priority;
    private String status;
    private String summary;
    private String sourceUrl;

    private String matchStatus;
    private String supportType;
    private String supportSummary;
    private String repaymentSummary;
    private String applicationPeriod;
    private String reason;
    private List<String> requiredChecks;
}
