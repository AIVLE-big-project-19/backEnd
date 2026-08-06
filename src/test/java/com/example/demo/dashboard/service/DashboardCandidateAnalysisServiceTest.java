package com.example.demo.dashboard.service;

import com.example.demo.analysis.service.AnalysisSnapshotService;
import com.example.demo.dashboard.client.PvgisClient;
import com.example.demo.idleland.client.MlScoringClient;
import com.example.demo.idleland.dto.MlRankResponse;
import com.example.demo.idleland.entity.IdleLand;
import com.example.demo.idleland.repository.IdleLandRepository;
import com.example.demo.idleland.service.VisionEnrichmentService;
import com.example.demo.report.dto.AiAnalysisResponse;
import com.example.demo.report.dto.DetailScores;
import com.example.demo.report.dto.ScoresAndEvaluation;
import com.example.demo.report.dto.Simulation;
import com.example.demo.report.dto.SiteInfo;
import com.example.demo.report.dto.VisionAiSimulation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

@ExtendWith(MockitoExtension.class)
class DashboardCandidateAnalysisServiceTest {

    @Mock
    private IdleLandRepository idleLandRepository;

    @Mock
    private MlScoringClient mlScoringClient;

    @Mock
    private VisionEnrichmentService visionEnrichmentService;

    @Mock
    private PvgisClient pvgisClient;

    @Mock
    private AnalysisSnapshotService analysisSnapshotService;

    @InjectMocks
    private DashboardCandidateAnalysisService service;

    @BeforeEach
    void setUpPvgisFallback() {
        lenient().when(pvgisClient.forecast(any(PvgisClient.Request.class))).thenReturn(Optional.empty());
    }

    @Test
    void ML_상세결과를_대시보드_응답으로_변환한다() {
        IdleLand idleLand = IdleLand.builder()
                .id(7L)
                .sourceId("SITE-7")
                .address("충청남도 홍성군")
                .assetTypeNorm("BUILDING")
                .latitude(36.6)
                .longitude(126.6)
                .build();
        AiAnalysisResponse analysis = analysis();
        MlRankResponse rankResponse = new MlRankResponse();
        rankResponse.setTopCandidates(List.of(analysis));

        when(idleLandRepository.findById(7L)).thenReturn(Optional.of(idleLand));
        when(mlScoringClient.rank("building", List.of(idleLand), 1, true)).thenReturn(rankResponse);

        var result = service.analyze(7L, 1L);

        assertThat(result.id()).isEqualTo(7L);
        assertThat(result.siteType()).isEqualTo("ROOF");
        assertThat(result.suitabilityScore()).isEqualTo(91);
        assertThat(result.capacityKw()).isEqualTo(120);
        assertThat(result.capacityEstimate().registeredType()).isEqualTo("ROOF");
        assertThat(result.capacityEstimate().areaPerKwM2()).isEqualTo(7.5d);
        assertThat(result.capacityEstimate().availableAreaM2()).isEqualTo(900d);
        assertThat(result.economicAssumptions().installationCostPerKw()).isEqualTo(1_300_000L);
        assertThat(result.economicAssumptions().annualOmRatePercent()).isEqualTo(1.5d);
        assertThat(result.scores().ml()).isEqualTo(93);
        assertThat(result.roofAnalysis().slopeDegrees()).isEqualTo(12d);
        verify(mlScoringClient).rank("building", List.of(idleLand), 1, true);
    }

    @Test
    void Vision_AI_realArea로_경제성_누락값을_계산한다() {
        IdleLand idleLand = IdleLand.builder()
                .id(8L)
                .sourceId("SITE-8")
                .address("충청남도 천안시")
                .assetTypeNorm("LAND")
                .latitude(36.9)
                .longitude(127.1)
                .build();
        AiAnalysisResponse analysis = analysis();
        analysis.getSiteInfo().setAvailableArea(null);
        analysis.getSiteInfo().setAvailabilityRatePercent(null);
        analysis.getVisionAiSimulation().setSimulation(null);
        MlRankResponse rankResponse = new MlRankResponse();
        rankResponse.setTopCandidates(List.of(analysis));

        when(idleLandRepository.findById(8L)).thenReturn(Optional.of(idleLand));
        when(mlScoringClient.rank("land", List.of(idleLand), 1, true)).thenReturn(rankResponse);
        // VisionEnrichmentService는 별도로 단위 테스트되는 컴포넌트이므로, 여기서는
        // 그 결과로 AiAnalysisResponse가 어떻게 채워지는지만 흉내 낸다
        // (실제로는 VWorld -> Vision AI -> ML(/analyze/vision-json)을 거쳐 같은 값이 채워짐).
        doAnswer(invocation -> {
            AiAnalysisResponse target = invocation.getArgument(1);
            target.getSiteInfo().setAvailableArea(186.69d);
            target.getSiteInfo().setAvailabilityRatePercent(12.45d);
            Simulation visionSimulation = new Simulation();
            visionSimulation.setRecommendedCapacityKw(25);
            VisionAiSimulation vision = target.getVisionAiSimulation();
            vision.setSimulation(visionSimulation);
            vision.setVisionAnalysis(Map.of("candidate_type", "building"));
            return null;
        }).when(visionEnrichmentService).enrich(eq(idleLand), any(AiAnalysisResponse.class));

        var result = service.analyze(8L, 1L);

        assertThat(result.usableRoofAreaM2()).isEqualTo(186.69d);
        assertThat(result.roofUtilizationRate()).isEqualTo(12.45d);
        assertThat(result.capacityKw()).isEqualTo(19);
        assertThat(result.capacityEstimate().registeredType()).isEqualTo("LAND");
        assertThat(result.capacityEstimate().visionType()).isEqualTo("ROOF");
        assertThat(result.capacityEstimate().areaPerKwM2()).isEqualTo(10d);
        assertThat(result.capacityEstimate().source()).isEqualTo("REGISTERED_TYPE_AREA");
        assertThat(result.annualGenerationKwh()).isEqualTo(24_700L);
        assertThat(result.estimatedAnnualRevenue()).isEqualTo(3_952_000L);
        assertThat(result.roiPercent()).isEqualTo(15.8d);
        assertThat(result.paybackPeriodYears()).isEqualTo(6.3d);
        assertThat(result.economicAssumptions().installationCostPerKw()).isEqualTo(1_200_000L);
        assertThat(result.economicAssumptions().estimatedInstallationCost()).isEqualTo(22_800_000L);
        assertThat(result.economicAssumptions().estimatedAnnualOmCost()).isEqualTo(342_000L);
        assertThat(result.economicAssumptions().estimatedAnnualNetIncome()).isEqualTo(3_610_000L);
        assertThat(result.generationForecast().fallback()).isTrue();
        assertThat(result.generationForecast().monthly()).hasSize(12);
        assertThat(result.generationForecast().monthly().stream()
                .mapToLong(item -> item.generationKwh()).sum()).isEqualTo(24_700L);
    }

    @Test
    void PVGIS_월별_발전량을_연간_발전량과_경제성에_반영한다() {
        IdleLand idleLand = IdleLand.builder()
                .id(9L)
                .sourceId("SITE-9")
                .address("충청남도 홍성군")
                .assetTypeNorm("BUILDING")
                .latitude(36.6)
                .longitude(126.6)
                .build();
        MlRankResponse rankResponse = new MlRankResponse();
        rankResponse.setTopCandidates(List.of(analysis()));
        List<PvgisClient.MonthlyGeneration> monthly = java.util.stream.IntStream.rangeClosed(1, 12)
                .mapToObj(month -> new PvgisClient.MonthlyGeneration(month, 12_000L))
                .toList();

        when(idleLandRepository.findById(9L)).thenReturn(Optional.of(idleLand));
        when(mlScoringClient.rank("building", List.of(idleLand), 1, true)).thenReturn(rankResponse);
        when(pvgisClient.forecast(any(PvgisClient.Request.class))).thenReturn(Optional.of(
                new PvgisClient.Forecast(
                        "PVGIS 5.3 / ERA5",
                        PvgisClient.METHOD,
                        120,
                        30d,
                        0d,
                        14d,
                        false,
                        monthly,
                        144_000L
                )
        ));

        var result = service.analyze(9L, 1L);

        assertThat(result.annualGenerationKwh()).isEqualTo(144_000L);
        assertThat(result.estimatedAnnualRevenue()).isEqualTo(23_040_000L);
        assertThat(result.roiPercent()).isEqualTo(13.3d);
        assertThat(result.paybackPeriodYears()).isEqualTo(7.5d);
        assertThat(result.economicAssumptions().installationCostPerKw()).isEqualTo(1_300_000L);
        assertThat(result.economicAssumptions().estimatedAnnualOmCost()).isEqualTo(2_340_000L);
        assertThat(result.generationForecast().source()).isEqualTo("PVGIS 5.3 / ERA5");
        assertThat(result.generationForecast().fallback()).isFalse();
        assertThat(result.generationForecast().monthly()).hasSize(12);
    }

    @Test
    void 주차장형은_kW당_150만원과_연간_O_and_M_1_5퍼센트를_적용한다() {
        IdleLand idleLand = IdleLand.builder()
                .id(10L)
                .sourceId("SITE-10")
                .address("충청남도 주차장 후보지")
                .assetTypeNorm("PARKING_LOT")
                .latitude(36.6)
                .longitude(126.6)
                .build();
        MlRankResponse rankResponse = new MlRankResponse();
        rankResponse.setTopCandidates(List.of(analysis()));

        when(idleLandRepository.findById(10L)).thenReturn(Optional.of(idleLand));
        when(mlScoringClient.rank("land", List.of(idleLand), 1, true)).thenReturn(rankResponse);

        var result = service.analyze(10L, 1L);

        assertThat(result.siteType()).isEqualTo("PARKING_LOT");
        assertThat(result.capacityKw()).isEqualTo(90);
        assertThat(result.economicAssumptions().installationCostPerKw()).isEqualTo(1_500_000L);
        assertThat(result.economicAssumptions().estimatedInstallationCost()).isEqualTo(135_000_000L);
        assertThat(result.economicAssumptions().estimatedAnnualOmCost()).isEqualTo(2_025_000L);
        assertThat(result.economicAssumptions().estimatedAnnualNetIncome()).isEqualTo(16_695_000L);
        assertThat(result.roiPercent()).isEqualTo(12.4d);
        assertThat(result.paybackPeriodYears()).isEqualTo(8.1d);
    }

    @Test
    void PVGIS가_실패하면_후보지별_pvout_avg_daily를_우선_적용한다() {
        IdleLand idleLand = IdleLand.builder()
                .id(11L)
                .sourceId("SITE-11")
                .address("충청남도 pvout 후보지")
                .assetTypeNorm("LAND")
                .latitude(36.6)
                .longitude(126.6)
                .pvoutAvgDaily(4.0d)
                .build();
        MlRankResponse rankResponse = new MlRankResponse();
        rankResponse.setTopCandidates(List.of(analysis()));

        when(idleLandRepository.findById(11L)).thenReturn(Optional.of(idleLand));
        when(mlScoringClient.rank("land", List.of(idleLand), 1, true)).thenReturn(rankResponse);

        var result = service.analyze(11L, 1L);

        assertThat(result.capacityKw()).isEqualTo(90);
        assertThat(result.annualGenerationKwh()).isEqualTo(131_400L);
        assertThat(result.generationForecast().source()).isEqualTo("후보지 pvout_avg_daily");
        assertThat(result.generationForecast().method()).isEqualTo("PVOUT_DAILY_SPECIFIC_YIELD");
        assertThat(result.generationForecast().pvoutAvgDaily()).isEqualTo(4.0d);
        assertThat(result.generationForecast().specificYieldKwhPerKwpYear()).isEqualTo(1_460d);
        assertThat(result.generationForecast().monthly()).hasSize(12);
        assertThat(result.generationForecast().monthly().stream()
                .mapToLong(item -> item.generationKwh()).sum()).isEqualTo(131_400L);
        assertThat(result.roiPercent()).isEqualTo(18d);
        assertThat(result.paybackPeriodYears()).isEqualTo(5.6d);
    }

    private AiAnalysisResponse analysis() {
        SiteInfo site = new SiteInfo();
        site.setAddress("충청남도 홍성군 신청사");
        site.setTotalArea(1_500d);
        site.setAvailableArea(900d);
        site.setAvailabilityRatePercent(60d);

        DetailScores details = new DetailScores();
        details.setMlTechnicalScore(93);
        details.setVisionAiScore(88);
        details.setRuleBasedScore(92);

        ScoresAndEvaluation evaluation = new ScoresAndEvaluation();
        evaluation.setTotalScore(91);
        evaluation.setGrade("A");
        evaluation.setPriorityRank("전체 7위");
        evaluation.setDetailScores(details);

        Simulation simulation = new Simulation();
        simulation.setRecommendedCapacityKw(120);
        simulation.setAnnualGenerationKwh(156_000L);
        simulation.setAnnualRevenueKrw(24_960_000L);
        simulation.setRoiPercent(11.2);
        simulation.setPaybackYears(8.9);

        VisionAiSimulation vision = new VisionAiSimulation();
        vision.setSimulation(simulation);
        vision.setVisionAnalysis(Map.of(
                "roof_structure_type", "평지붕",
                "roof_slope_deg", 12,
                "recommended_orientation", "정남향",
                "recommended_tilt_angle_deg", 30
        ));

        AiAnalysisResponse analysis = new AiAnalysisResponse();
        analysis.setTargetType("ROOF");
        analysis.setSiteInfo(site);
        analysis.setScoresAndEvaluation(evaluation);
        analysis.setVisionAiSimulation(vision);
        return analysis;
    }
}
