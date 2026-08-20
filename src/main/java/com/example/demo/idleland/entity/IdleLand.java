package com.example.demo.idleland.entity;

import com.example.demo.global.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "idle_land", indexes = {
        @Index(name = "idx_idle_land_address", columnList = "address"),
        @Index(name = "idx_idle_land_source_id", columnList = "sourceId")
})
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IdleLand extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(length = 100)
    private String sourceId;

    @Column(length = 500)
    private String address;

    private Double longitude;
    private Double latitude;

    @Column(length = 50)
    private String sido;

    @Column(length = 50)
    private String sigungu;

    @Column(length = 50)
    private String assetTypeRaw;

    @Column(length = 50)
    private String installationType;

    private Integer label;

    private Double ghiAvgDaily;
    private Double pvoutAvgDaily;
    private Double dniAvgDaily;
    private Double difAvgDaily;
    private Double gtiAvgDaily;
    private Double tempAvg;
    private Double windSpeed10m;
    private Double windSpeed50m;
    private Double windSpeed100m;
    private Double slopeAvg;
    private Double slopeDir;
    private Double elevationAvg;
    private Double hillshade;
    private Double southness;
    private Double distanceToSubstationKm;
    private Double distanceToPowerlineKm;
    private Integer substationCount5km;
    private Double powerlineLength5kmKm;
    private Integer highVoltageLineNearby5km;
    private Double substationMaxVoltageKv;
    private Double powerlineMaxVoltageKv;
    private Integer substationMaxVoltageKvMissing;
    private Integer powerlineMaxVoltageKvMissing;

    private Integer assetTypeCode;

    @Column(length = 50)
    private String regionGroup;

    @Column(length = 20)
    private String assetTypeNorm;

    private Integer estimatedPanelCount;
    private Double visionScore;

    @Column(columnDefinition = "TEXT")
    private String parcelGeometryJson;

    @Column(columnDefinition = "LONGTEXT")
    private String panelLayoutJson;

    public void applyVisionScore(Double visionScore) {
        this.visionScore = visionScore;
    }

    private Double solarReadinessScore;

    @Column(length = 10)
    private String solarReadinessGrade;

    private Integer candidateRank;

    public void applyScore(Double mlScore, String grade, Integer rank) {
        this.solarReadinessScore = combineWithVision(mlScore);
        this.solarReadinessGrade = grade;
        this.candidateRank = rank;
    }

    public void applyGrade(String grade) {
        this.solarReadinessGrade = grade;
    }

    private Double combineWithVision(Double mlScore) {
        if (mlScore == null) return visionScore;
        if (visionScore == null) return mlScore;
        return (mlScore + visionScore) / 2;
    }

    public String mlSourceId() {
        return sourceId != null ? sourceId : "IDLE_" + id;
    }
}
