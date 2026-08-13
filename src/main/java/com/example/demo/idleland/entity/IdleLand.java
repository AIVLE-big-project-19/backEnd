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

// 관리자가 업로드하는 유휴부지 원본(Uninstalled) CSV 스키마(34개 컬럼)를 그대로 담는 엔티티.
// Rule-based 검토 이전 단계의 원본 후보지 데이터이며, 검색·ML 채점의 기준 데이터가 된다.
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
    private String sourceId; // source_id_ml

    @Column(length = 500)
    private String address; // address_ml

    private Double longitude;
    private Double latitude;

    @Column(length = 50)
    private String sido; // 시도

    @Column(length = 50)
    private String sigungu; // 시군구

    @Column(length = 50)
    private String assetTypeRaw; // 자산구분_ML (원본 값, 예: 토지/건물)

    @Column(length = 50)
    private String installationType; // 설치구분

    private Integer label;

    // GIS / 기상 feature (ML 모델 입력값)
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

    public void applyParcelData(String parcelGeometryJson, String panelLayoutJson) {
        this.parcelGeometryJson = parcelGeometryJson;
        this.panelLayoutJson = panelLayoutJson;
    }

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

    // Ranking_ML이 준 grade는 Vision 반영 전 순수 ML 점수 기준이라, solarReadinessScore(ML+Vision
    // 블렌딩 값)의 백분위와 어긋날 수 있다. CSV 업로드 시점에 블렌딩 점수 기준으로 다시 계산한
    // grade로 덮어쓸 때 씀
    public void applyGrade(String grade) {
        this.solarReadinessGrade = grade;
    }

    private Double combineWithVision(Double mlScore) {
        if (mlScore == null) return visionScore;
        if (visionScore == null) return mlScore;
        return (mlScore + visionScore) / 2;
    }

    // ML 서버로 보내는 CSV의 source_id_ml 값. 원본 sourceId가 비어있으면 DB PK로 대체해
    // ML 응답과 다시 매칭할 때 항상 유일한 키를 쓸 수 있게 한다.
    public String mlSourceId() {
        return sourceId != null ? sourceId : "IDLE_" + id;
    }
}
