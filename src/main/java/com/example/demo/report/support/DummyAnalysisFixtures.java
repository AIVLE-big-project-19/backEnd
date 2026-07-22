package com.example.demo.report.support;

import com.example.demo.report.dto.AiAnalysisResponse;
import com.example.demo.report.dto.ChecklistItem;
import com.example.demo.report.dto.DetailScores;
import com.example.demo.report.dto.RiskAndSupport;
import com.example.demo.report.dto.RiskCheck;
import com.example.demo.report.dto.ScoresAndEvaluation;
import com.example.demo.report.dto.SiteInfo;
import com.example.demo.report.dto.Simulation;
import com.example.demo.report.dto.VisionAiSimulation;
import com.example.demo.report.dto.XaiExplanation;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// AI 분석 서버 연동 전 PDF 레이아웃 확인용 더미 데이터
//추후 제거 예정
public class DummyAnalysisFixtures {

    private DummyAnalysisFixtures() {
    }

    public static AiAnalysisResponse roofSample() {
        AiAnalysisResponse data = new AiAnalysisResponse();
        data.setTargetType("ROOF");

        SiteInfo site = new SiteInfo();
        site.setSiteId("SITE_2026_0721_001");
        site.setSiteName("충남도청사 본관 옥상");
        site.setAddress("충청남도 홍성군 홍북읍 충남대로 21");
        site.setSpaceType("공공건축물 지붕 / 슬래브 옥상");
        site.setTotalArea(1200.0);
        site.setAvailableArea(850.0);
        site.setAvailabilityRatePercent(82.5);
        site.setOwnerAgency("충청남도청 (기산관리과)");
        site.setCreatedAt("2026년 07월 21일");
        data.setSiteInfo(site);

        DetailScores detailScores = new DetailScores();
        detailScores.setMlTechnicalScore(95);
        detailScores.setMlReason("연평균 일사량 우수(3.7 kWh/m²/day), 계통연계 거리 150m 이내");
        detailScores.setVisionAiScore(98);
        detailScores.setVisionReason("평지붕 구조, 장애물 적음, 고정식 패널 최적 배치 가능");
        detailScores.setRuleBasedScore(100);
        detailScores.setRuleReason("공공 유휴부지 특례 적용으로 이격거리 규제 미적용");

        XaiExplanation xai = new XaiExplanation();
        xai.setBonusReason(List.of(
                "연계 변전소와의 거리 단축으로 인한 초기 구축 비용 절감",
                "공공건물 활용에 따른 민원 가능성 최소화"
        ));
        xai.setPenaltyReason(List.of(
                "옥상 구조물 주변에 약 5% 수준의 수평 음영 구역 존재"
        ));

        ScoresAndEvaluation scores = new ScoresAndEvaluation();
        scores.setGrade("A Grade");
        scores.setTotalScore(98);
        scores.setPriorityRank("충청남도 공공부지 중 1위");
        scores.setStatus("통과");
        scores.setDetailScores(detailScores);
        scores.setXaiExplanation(xai);
        data.setScoresAndEvaluation(scores);

        Map<String, Object> visionAnalysis = new LinkedHashMap<>();
        visionAnalysis.put("roof_structure_type", "평지붕 (콘크리트 슬래브)");
        visionAnalysis.put("roof_slope_deg", 15.0);
        visionAnalysis.put("obstacle_shading_ratio_percent", 17.5);
        visionAnalysis.put("obstacle_shading_area", 150.0);
        visionAnalysis.put("recommended_orientation", "정남향");
        visionAnalysis.put("recommended_tilt_angle_deg", 30);

        Simulation simulation = new Simulation();
        simulation.setRecommendedCapacityKw(100);
        simulation.setAnnualGenerationKwh(135000L);
        simulation.setAnnualRevenueKrw(24000000L);
        simulation.setRoiPercent(12.4);
        simulation.setPaybackYears(6.5);

        VisionAiSimulation visionAiSimulation = new VisionAiSimulation();
        visionAiSimulation.setVisionAnalysis(visionAnalysis);
        visionAiSimulation.setSimulation(simulation);
        data.setVisionAiSimulation(visionAiSimulation);

        RiskCheck riskCheck = new RiskCheck();
        riskCheck.setGridConnection("인근 변전소 계통 용량 여유 확인 완료 (연계 가능)");
        riskCheck.setRegulation("지자체 태양광 이격거리 조례 대상 외 (공공청사 옥상 설치건)");
        riskCheck.setPublicComplaint("주거지역과 이격되어 있으며 반사광 피해 영향 분석 결과 '안전' 등급");

        RiskAndSupport riskAndSupport = new RiskAndSupport();
        riskAndSupport.setRuleBasedRiskCheck(riskCheck);
        riskAndSupport.setRecommendedSubsidies(List.of(
                "2026년 신재생에너지 지역지원사업",
                "공공기관 태양광 보급 확대 지원사업"
        ));
        data.setRiskAndSupport(riskAndSupport);

        data.setPreInvestigationChecklist(List.of(
                checklistItem("지붕 방수 및 누수 상태 확인", "시공 전 옥상 우수 방수층 사전 점검 필요"),
                checklistItem("건축물 구조안전진단 수행", "100kW 패널 하중 견딤 여부 안전진단 의뢰"),
                checklistItem("변전실 인입선로 용량 및 분전반 위치", "건물 내 전기실 접속점까지 배선 경로 확인")
        ));

        return data;
    }

    public static AiAnalysisResponse landSample() {
        AiAnalysisResponse data = new AiAnalysisResponse();
        data.setTargetType("LAND");

        SiteInfo site = new SiteInfo();
        site.setSiteId("SITE_2026_0721_002");
        site.setSiteName("당진시 석문면 잡종지 유휴부지");
        site.setAddress("충청남도 당진시 석문면 통정리 123-4");
        site.setSpaceType("평지 유휴부지 (잡종지)");
        site.setTotalArea(3500.0);
        site.setAvailableArea(2800.0);
        site.setAvailabilityRatePercent(80.0);
        site.setOwnerAgency("당진시청 (자산관리과)");
        site.setCreatedAt("2026년 07월 21일");
        data.setSiteInfo(site);

        DetailScores detailScores = new DetailScores();
        detailScores.setMlTechnicalScore(88);
        detailScores.setMlReason("연평균 일사량 보통(3.5 kWh/m²/day), 삼상전주 연장 거리 250m 필요");
        detailScores.setVisionAiScore(82);
        detailScores.setVisionReason("평탄 지형, 동측 수목으로 인한 부분 음영 존재, 진입로 4m 확보");
        detailScores.setRuleBasedScore(85);
        detailScores.setRuleReason("도로 및 주거지 이격거리 조례 기준 충족");

        XaiExplanation xai = new XaiExplanation();
        xai.setBonusReason(List.of(
                "지목이 잡종지로 농지보전부담금 감면 대상",
                "진입로(4m)가 확보되어 대형 공사차량 진입 용이"
        ));
        xai.setPenaltyReason(List.of(
                "가까운 삼상전주까지 250m 선로 신설 공사비 추가 발생",
                "동측 수목으로 인한 아침 시간대 약 5% 수평 음영 영향"
        ));

        ScoresAndEvaluation scores = new ScoresAndEvaluation();
        scores.setGrade("B Grade");
        scores.setTotalScore(85);
        scores.setPriorityRank("당진시 시유지 중 5위");
        scores.setStatus("통과");
        scores.setDetailScores(detailScores);
        scores.setXaiExplanation(xai);
        data.setScoresAndEvaluation(scores);

        Map<String, Object> visionAnalysis = new LinkedHashMap<>();
        visionAnalysis.put("slope_degree", 4.2);
        visionAnalysis.put("aspect_direction", "남서향");
        visionAnalysis.put("vegetation_coverage_percent", 15.0);
        visionAnalysis.put("has_access_road", true);
        visionAnalysis.put("access_road_width_m", 4.0);
        visionAnalysis.put("recommended_orientation", "정남향");
        visionAnalysis.put("recommended_tilt_angle_deg", 25);

        Simulation simulation = new Simulation();
        simulation.setRecommendedCapacityKw(300);
        simulation.setAnnualGenerationKwh(405000L);
        simulation.setAnnualRevenueKrw(72000000L);
        simulation.setRoiPercent(10.8);
        simulation.setPaybackYears(7.2);

        VisionAiSimulation visionAiSimulation = new VisionAiSimulation();
        visionAiSimulation.setVisionAnalysis(visionAnalysis);
        visionAiSimulation.setSimulation(simulation);
        data.setVisionAiSimulation(visionAiSimulation);

        RiskCheck riskCheck = new RiskCheck();
        riskCheck.setGridConnection("석문변전소 계통 용량 여유 확인 완료 (250m 삼상선로 연장 필요)");
        riskCheck.setRegulation("도로 이격거리(150m/기준 100m) 및 주거지 이격거리(350m/기준 200m) 충족");
        riskCheck.setPublicComplaint("주거 밀집지역과 충분히 이격되어 반사광 및 주민 민원 리스크 '낮음' 등급");

        RiskAndSupport riskAndSupport = new RiskAndSupport();
        riskAndSupport.setRuleBasedRiskCheck(riskCheck);
        riskAndSupport.setRecommendedSubsidies(List.of(
                "2026년 농촌 태양광 융자 지원사업",
                "지자체 영농형 및 유휴지 태양광 보급 활성화 사업"
        ));
        data.setRiskAndSupport(riskAndSupport);

        data.setPreInvestigationChecklist(List.of(
                checklistItem("진입로 확보 및 공사차량(25톤) 진입 가능 여부", "진출입 도로점용 허가 및 현장 도로 폭 실측"),
                checklistItem("토질 상태 및 배수/우수관로 확보 가능 여부", "장마철 토사 유출 우려 및 배수 구조물 설치 공간 확인"),
                checklistItem("가까운 3상 전주 위치 및 연장 구간 측량", "한전 삼상선로 250m 연장 토목 공사비 산정"),
                checklistItem("인근 마을 주민 민원 동의 및 개발행위허가 측량", "개발행위허가 제출용 공식 경계 및 이격거리 측량")
        ));

        return data;
    }

    private static ChecklistItem checklistItem(String item, String note) {
        ChecklistItem checklistItem = new ChecklistItem();
        checklistItem.setItem(item);
        checklistItem.setNote(note);
        return checklistItem;
    }
}
