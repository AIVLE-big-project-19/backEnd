package com.example.demo.report.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class Simulation {

    @JsonProperty("recommended_capacity_kw")
    private Integer recommendedCapacityKw;

    @JsonProperty("annual_generation_kwh")
    private Long annualGenerationKwh;

    @JsonProperty("annual_revenue_krw")
    private Long annualRevenueKrw;

    @JsonProperty("roi_percent")
    private Double roiPercent;

    @JsonProperty("payback_years")
    private Double paybackYears;
}
