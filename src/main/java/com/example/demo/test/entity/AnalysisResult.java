package com.example.demo.test.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;


@Entity
@Getter
@Setter
@NoArgsConstructor
public class AnalysisResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long userId;
    private String imageUrl;

    @Enumerated(EnumType.STRING)
    private AnalysisStatus status; // IN_PROGRESS, COMPLETED, FAILED

    // Vision AI 결과 (음영 분석)
    private Double shadeRatio;       // 음영 비율
    private Double sunlightHours;    // 일조 시간
    private Double usableAreaSqm;    // 유효 면적

    // ML 결과 (적합도 선정)
    private String recommendedModel; // 추천 모델/장비
    private Double suitabilityScore; // 적합도 점수

    private String location;

    public enum AnalysisStatus {
        IN_PROGRESS, COMPLETED, FAILED
    }
}