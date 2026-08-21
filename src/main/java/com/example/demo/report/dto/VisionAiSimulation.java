package com.example.demo.report.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;

@Getter
@Setter
public class VisionAiSimulation {

    @JsonProperty("vision_analysis")
    private Map<String, Object> visionAnalysis;

    private Simulation simulation;
}
