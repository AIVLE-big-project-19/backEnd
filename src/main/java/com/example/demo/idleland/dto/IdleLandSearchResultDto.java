package com.example.demo.idleland.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class IdleLandSearchResultDto {

    private Long id;
    private String sourceId;
    private String address;
    private Double longitude;
    private Double latitude;
    private String sido;
    private String sigungu;
    private String assetType;
    private Double solarReadinessScore;
    private String solarReadinessGrade;
    private Integer candidateRank;
}
