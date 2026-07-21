package com.example.demo.dashboard.config;

import com.example.demo.dashboard.entity.SiteAnalysis;
import com.example.demo.dashboard.repository.SiteAnalysisRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.demo-data.enabled", havingValue = "true", matchIfMissing = true)
public class DashboardDemoDataInitializer implements ApplicationRunner {
    private final SiteAnalysisRepository siteAnalysisRepository;

    private static final List<DemoSite> DEMO_SITES = List.of(
            new DemoSite("경기도 수원시 영통구 광교중앙로 140", 520d, 52d, 88, 92, 84, 86),
            new DemoSite("경기도 용인시 처인구 포곡읍 에버랜드로 199", 780d, 78d, 84, 89, 79, 83),
            new DemoSite("충청남도 아산시 배방읍 희망로 100", 410d, 41d, 81, 85, 78, 79),
            new DemoSite("전라남도 나주시 빛가람로 739", 650d, 65d, 76, 80, 70, 76),
            new DemoSite("강원도 춘천시 중앙로 1", 320d, 32d, 62, 68, 58, 60)
    );

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<SiteAnalysis> missingSites = DEMO_SITES.stream()
                .filter(site -> !siteAnalysisRepository.existsByUserIsNullAndAddress(site.address()))
                .map(this::toEntity)
                .toList();
        if (!missingSites.isEmpty()) {
            siteAnalysisRepository.saveAll(missingSites);
        }
    }

    private SiteAnalysis toEntity(DemoSite site) {
        double generation = site.capacityKw() * 1_300d;
        long cost = Math.round(site.capacityKw() * 1_300_000L);
        long revenue = Math.round(generation * 160L);
        return SiteAnalysis.builder()
                .address(site.address()).areaM2(site.areaM2()).capacityKw(site.capacityKw())
                .suitabilityScore(site.score()).irradiationScore(site.irradiationScore())
                .terrainScore(site.terrainScore()).accessScore(site.accessScore())
                .annualGenerationKwh(generation).estimatedInstallationCost(cost)
                .estimatedAnnualRevenue(revenue).paybackPeriodYears(Math.round((cost / (double) revenue) * 10d) / 10d)
                .build();
    }

    private record DemoSite(String address, Double areaM2, Double capacityKw, int score,
                            int irradiationScore, int terrainScore, int accessScore) { }
}
