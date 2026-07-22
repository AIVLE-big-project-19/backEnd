package com.example.demo.report.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;

@Getter
@Setter
public class VisionAiSimulation {

    // ROOF/LAND에 따라 필드 구성이 달라서 Map으로 받고, 렌더링 시 target_type별 라벨에 맞춰 꺼내 씀
    @JsonProperty("vision_analysis")
    private Map<String, Object> visionAnalysis;

    private Simulation simulation;
}
