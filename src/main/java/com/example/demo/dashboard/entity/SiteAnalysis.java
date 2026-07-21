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
}
