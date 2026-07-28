package com.example.demo.recommend.client.dto;

import com.example.demo.report.dto.AiAnalysisResponse;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;

@Getter
@Setter
public class JobResult {

    private Map<String, Object> funnel;

    private List<AiAnalysisResponse> recommendations;
}
