package com.example.demo.fastApi.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "solar_analysis")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class SolarAnalysis {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String candidateType;
    private Double confidence;

    // Polygon 좌표 배열을 JSON 문자열 형태로 저장
    @Column(columnDefinition = "TEXT")
    private String polygonJson;

    private Double pixelArea;
    private Double realArea; // 실제 면적 (m²)
    private String modelVersion;

    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        this.createdAt = LocalDateTime.now();
    }
}