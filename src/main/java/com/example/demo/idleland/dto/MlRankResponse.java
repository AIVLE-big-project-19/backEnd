package com.example.demo.idleland.dto;

import com.example.demo.report.dto.AiAnalysisResponse;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;

@Getter
@Setter
@JsonIgnoreProperties(ignoreUnknown = true)
public class MlRankResponse {

    @JsonProperty("dataset_type")
    private String datasetType;

    @JsonProperty("model_name")
    private String modelName;

    @JsonProperty("input_rows")
    private Integer inputRows;

    @JsonProperty("ranked_rows")
    private Integer rankedRows;

    private List<Map<String, Object>> ranking;

    @JsonProperty("top_candidates")
    private List<AiAnalysisResponse> topCandidates;
}
