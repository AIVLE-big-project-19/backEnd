package com.example.demo.fastApi.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.util.List;

@Data
public class PredictionResponseDto {

    private List<PredictionDto> predictions;

    @JsonProperty("annotated_image")
    private String annotatedImage;

    @Data
    public static class PredictionDto {
        @JsonProperty("candidate_type")
        private String candidateType;

        private Double confidence;
        private List<List<Double>> polygon;

        @JsonProperty("pixel_area")
        private Double pixelArea;

        @JsonProperty("real_area")
        private Double realArea;

        @JsonProperty("model_version")
        private String modelVersion;
    }
}