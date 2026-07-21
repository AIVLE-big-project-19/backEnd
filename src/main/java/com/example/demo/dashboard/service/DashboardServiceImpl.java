package com.example.demo.dashboard.service;

import com.example.demo.dashboard.dto.SiteAnalysisRequest;
import com.example.demo.dashboard.dto.SiteAnalysisResponse;
import com.example.demo.dashboard.entity.SiteAnalysis;
import com.example.demo.dashboard.repository.SiteAnalysisRepository;
import com.example.demo.user.entity.User;
import com.example.demo.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DashboardServiceImpl implements DashboardService {
    private static final double GENERATION_PER_KW = 1_300d;
    private static final long INSTALLATION_COST_PER_KW = 1_300_000L;
    private static final long REVENUE_PER_KWH = 160L;

    private final SiteAnalysisRepository siteAnalysisRepository;
    private final UserRepository userRepository;

    @Override
    @Transactional
    public SiteAnalysisResponse analyze(Long userId, SiteAnalysisRequest request) {
        double area = request.areaM2() == null ? 100d : request.areaM2();
        double capacity = request.capacityKw() == null ? Math.max(3d, Math.round(area / 10d * 10d) / 10d) : request.capacityKw();
        int seed = Math.abs(request.address().hashCode());
        int irradiation = 70 + seed % 26;
        int terrain = 65 + (seed / 31) % 31;
        int access = 60 + (seed / 997) % 36;
        int suitability = Math.round(irradiation * 0.5f + terrain * 0.3f + access * 0.2f);
        double annualGeneration = Math.round(capacity * GENERATION_PER_KW * 10d) / 10d;
        long cost = Math.round(capacity * INSTALLATION_COST_PER_KW);
        long revenue = Math.round(annualGeneration * REVENUE_PER_KWH);
        double payback = Math.round((cost / (double) revenue) * 10d) / 10d;
        User user = userId == null ? null : userRepository.findById(userId).orElse(null);

        SiteAnalysis saved = siteAnalysisRepository.save(SiteAnalysis.builder()
                .user(user).address(request.address()).latitude(request.latitude()).longitude(request.longitude())
                .areaM2(area).capacityKw(capacity).suitabilityScore(suitability).irradiationScore(irradiation)
                .terrainScore(terrain).accessScore(access).annualGenerationKwh(annualGeneration)
                .estimatedInstallationCost(cost).estimatedAnnualRevenue(revenue).paybackPeriodYears(payback).build());
        return SiteAnalysisResponse.from(saved);
    }

    @Override
    public List<SiteAnalysisResponse> history(Long userId) {
        return siteAnalysisRepository.findTop10ByUser_IdOrderByCreatedAtDesc(userId).stream()
                .map(SiteAnalysisResponse::from).toList();
    }
}
