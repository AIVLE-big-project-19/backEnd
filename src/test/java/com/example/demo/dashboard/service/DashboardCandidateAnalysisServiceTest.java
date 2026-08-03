package com.example.demo.dashboard.service;

import com.example.demo.idleland.client.MlScoringClient;
import com.example.demo.idleland.client.VWorldImageClient;
import com.example.demo.idleland.client.VisionAiClient;
import com.example.demo.idleland.dto.MlRankResponse;
import com.example.demo.idleland.entity.IdleLand;
import com.example.demo.idleland.repository.IdleLandRepository;
import com.example.demo.report.dto.AiAnalysisResponse;
import com.example.demo.report.dto.DetailScores;
import com.example.demo.report.dto.ScoresAndEvaluation;
import com.example.demo.report.dto.Simulation;
import com.example.demo.report.dto.SiteInfo;
import com.example.demo.report.dto.VisionAiSimulation;
import org.junit.jupiter.api.Test;
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

@ExtendWith(MockitoExtension.class)
class DashboardCandidateAnalysisServiceTest {

    @Mock
    private IdleLandRepository idleLandRepository;

    @Mock
    private MlScoringClient mlScoringClient;

    @Mock
    private VWorldImageClient vWorldImageClient;

    @Mock
    private VisionAiClient visionAiClient;

    @InjectMocks
    private DashboardCandidateAnalysisService service;

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

        var result = service.analyze(7L);

        assertThat(result.id()).isEqualTo(7L);
        assertThat(result.siteType()).isEqualTo("ROOF");
        assertThat(result.suitabilityScore()).isEqualTo(91);
        assertThat(result.capacityKw()).isEqualTo(120);
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

        List<Map<String, Object>> predictions = List.of(Map.of("real_area", 186.69d));
        byte[] imageBytes = {1};
        VisionAiClient.VisionPredictResponse visionResponse = new VisionAiClient.VisionPredictResponse();
        visionResponse.setPredictions(predictions);

        when(idleLandRepository.findById(8L)).thenReturn(Optional.of(idleLand));
        when(mlScoringClient.rank("land", List.of(idleLand), 1, true)).thenReturn(rankResponse);
        when(vWorldImageClient.fetchImage(127.1d, 36.9d))
                .thenReturn(new VWorldImageClient.VisionImageSource(imageBytes, "extent"));
        when(visionAiClient.predict(imageBytes, "extent")).thenReturn(visionResponse);
        when(mlScoringClient.analyzeVisionJson(predictions)).thenReturn(Map.of(
                "results", List.of(Map.of(
                        "1_site_info", Map.of(
                                "total_area_m2", 1_500d,
                                "available_area_m2", 186.69d,
                                "availability_rate_percent", 12.45d
                        ),
                        "3_vision_ai_and_simulation", Map.of("vision_analysis", Map.of())
                ))
        ));

        var result = service.analyze(8L);

        assertThat(result.usableRoofAreaM2()).isEqualTo(186.69d);
        assertThat(result.roofUtilizationRate()).isEqualTo(12.45d);
        assertThat(result.capacityKw()).isEqualTo(19);
        assertThat(result.annualGenerationKwh()).isEqualTo(24_700L);
        assertThat(result.estimatedAnnualRevenue()).isEqualTo(3_952_000L);
        assertThat(result.roiPercent()).isEqualTo(16d);
        assertThat(result.paybackPeriodYears()).isEqualTo(6.3d);
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
