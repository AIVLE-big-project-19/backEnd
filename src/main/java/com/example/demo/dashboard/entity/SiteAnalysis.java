package com.example.demo.dashboard.entity;

import com.example.demo.global.entity.BaseEntity;
import com.example.demo.user.entity.User;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "site_analysis")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SiteAnalysis extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(nullable = false, length = 255)
    private String address;

    @Column(length = 20)
    private String siteType;

    // 참고: 공개 데모 후보지 목록에 노출할 초기 데이터인지 구분한다.
    @Column(nullable = false)
    private boolean demoData;

    private Double latitude;
    private Double longitude;
    private Double areaM2;
    private Double capacityKw;
    private Integer suitabilityScore;
    private Integer irradiationScore;
    private Integer terrainScore;
    private Integer accessScore;
    private Double annualGenerationKwh;
    private Long estimatedInstallationCost;
    private Long estimatedAnnualRevenue;
    private Double paybackPeriodYears;

    public void markAsDemoData(String siteType) {
        this.demoData = true;
        this.siteType = siteType;
    }
}
