package com.example.demo.report.service;

import com.example.demo.report.client.AiAnalysisClient;
import com.example.demo.report.support.DummyAnalysisFixtures;
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
import com.itextpdf.io.font.PdfEncodings;
import com.itextpdf.kernel.colors.DeviceRgb;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.borders.SolidBorder;
import com.itextpdf.layout.element.Cell;
import com.itextpdf.layout.element.Paragraph;
import com.itextpdf.layout.element.Table;
import com.itextpdf.layout.element.Text;
import com.itextpdf.layout.properties.TextAlignment;
import com.itextpdf.layout.properties.UnitValue;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ReportService {

    private static final DeviceRgb BRAND_BLUE = new DeviceRgb(27, 79, 145);
    private static final DeviceRgb LIGHT_BLUE_BG = new DeviceRgb(235, 242, 250);
    private static final DeviceRgb BORDER_GRAY = new DeviceRgb(210, 214, 220);
    private static final String TARGET_TYPE_ROOF = "ROOF";

    private final AiAnalysisClient aiAnalysisClient;

    public byte[] generateReportPdf(String address) throws Exception {
        AiAnalysisResponse data = aiAnalysisClient.fetchAnalysis(address);
        if (data == null) {
            throw new IllegalStateException("AI 분석 서버로부터 응답을 받지 못했습니다.");
        }
        return buildPdf(data);
    }

    // AI 서버 없이 PDF 레이아웃/데이터 매핑을 확인하기 위한 더미 데이터 경로
    public byte[] generateSampleReportPdf(String targetType) throws IOException {
        AiAnalysisResponse data = "LAND".equalsIgnoreCase(targetType)
                ? DummyAnalysisFixtures.landSample()
                : DummyAnalysisFixtures.roofSample();
        return buildPdf(data);
    }

    private byte[] buildPdf(AiAnalysisResponse data) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PdfWriter writer = new PdfWriter(baos);
        PdfDocument pdf = new PdfDocument(writer);
        Document document = new Document(pdf);

        PdfFont font = loadFont("malgun.ttf");
        PdfFont boldFont = loadFont("malgunbd.ttf");
        document.setFont(font);

        String targetType = data.getTargetType() != null ? data.getTargetType() : TARGET_TYPE_ROOF;

        document.add(new Paragraph("[SolarAivle] AI 태양광 유휴공간 입지 적합도 분석 검토 보고서")
                .setFont(boldFont).setFontSize(17).setFontColor(BRAND_BLUE).setMarginBottom(4));

        ScoresAndEvaluation scores = data.getScoresAndEvaluation();
        addGradeSummary(document, boldFont, scores);

        addSectionHeader(document, boldFont, "1. 대상 부지 기본 정보");
        addSiteInfoTable(document, font, boldFont, data.getSiteInfo(), targetType);

        addSectionHeader(document, boldFont, "2. AI 종합 적합도 평가 (Solar Readiness Score)");
        if (scores != null) {
            addScoreTable(document, font, boldFont, scores.getDetailScores());
            addXaiExplanation(document, font, boldFont, scores.getXaiExplanation());
        }

        addSectionHeader(document, boldFont, "3. Vision AI 영상 분석 및 발전/수익성 시뮬레이션");
        VisionAiSimulation visionAiSimulation = data.getVisionAiSimulation();
        if (visionAiSimulation != null) {
            document.add(new Paragraph("Vision AI 이미지 레이어 분석")
                    .setFont(boldFont).setFontSize(10.5f).setMarginBottom(4));
            addVisionAnalysisTable(document, font, boldFont, targetType, visionAiSimulation.getVisionAnalysis());

            document.add(new Paragraph("What-if 발전량 및 수익성 예측 시뮬레이션")
                    .setFont(boldFont).setFontSize(10.5f).setMarginTop(10).setMarginBottom(4));
            addSimulationTable(document, font, boldFont, visionAiSimulation.getSimulation());
        }

        addSectionHeader(document, boldFont, "4. 리스크 진단 및 연계 지원사업 추천");
        if (data.getRiskAndSupport() != null) {
            addRiskAndSupport(document, font, boldFont, data.getRiskAndSupport());
        }

        addSectionHeader(document, boldFont, "5. 현장조사 전 체크리스트 및 승인");
        addChecklist(document, font, boldFont, data.getPreInvestigationChecklist());

        document.add(new Paragraph("작성자: SolarAivle 자동 분석 시스템 | 검토자: ________________ (인)")
                .setFont(font).setFontSize(9).setMarginTop(20));

        document.close();
        return baos.toByteArray();
    }

    private PdfFont loadFont(String resourceName) throws IOException {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourceName)) {
            if (is == null) {
                throw new IOException("폰트를 찾을 수 없습니다: " + resourceName);
            }
            return PdfFontFactory.createFont(is.readAllBytes(), PdfEncodings.IDENTITY_H);
        }
    }

    private void addGradeSummary(Document document, PdfFont boldFont, ScoresAndEvaluation scores) {
        if (scores == null) {
            return;
        }
        StringBuilder line = new StringBuilder("종합 평가 결과: ")
                .append(safe(scores.getGrade()))
                .append(" (").append(scores.getTotalScore() != null ? scores.getTotalScore() : 0).append("점 / 100점)");
        if (scores.getPriorityRank() != null && !scores.getPriorityRank().isBlank()) {
            line.append(" | 우선순위: ").append(scores.getPriorityRank());
        }
        document.add(new Paragraph(line.toString())
                .setFont(boldFont).setFontSize(11)
                .setBackgroundColor(LIGHT_BLUE_BG)
                .setPadding(8)
                .setMarginBottom(10));
    }

    private void addSectionHeader(Document document, PdfFont boldFont, String title) {
        document.add(new Paragraph(title)
                .setFont(boldFont).setFontSize(13).setFontColor(BRAND_BLUE)
                .setMarginTop(16).setMarginBottom(6)
                .setBorderBottom(new SolidBorder(BRAND_BLUE, 1)));
    }

    private void addSiteInfoTable(Document document, PdfFont font, PdfFont boldFont, SiteInfo site, String targetType) {
        if (site == null) {
            return;
        }
        String availableAreaLabel = TARGET_TYPE_ROOF.equalsIgnoreCase(targetType) ? "가용 지붕 면적" : "가용 면적";

        Table table = new Table(UnitValue.createPercentArray(new float[]{22, 28, 22, 28})).useAllAvailableWidth();

        addLabelCell(table, boldFont, "공간명 / 주소");
        addValueCell(table, font, safe(site.getSiteName()) + "\n" + safe(site.getAddress()));
        addLabelCell(table, boldFont, "시설 / 공간 유형");
        addValueCell(table, font, safe(site.getSpaceType()));

        addLabelCell(table, boldFont, "전체 면적");
        addValueCell(table, font, formatArea(site.getTotalArea()));
        addLabelCell(table, boldFont, availableAreaLabel);
        String availableAreaValue = formatArea(site.getAvailableArea());
        if (site.getAvailabilityRatePercent() != null) {
            availableAreaValue += String.format(" (가용률 %.1f%%)", site.getAvailabilityRatePercent());
        }
        addValueCell(table, font, availableAreaValue);

        addLabelCell(table, boldFont, "소유 / 관리 기관");
        addValueCell(table, font, safe(site.getOwnerAgency()));
        addLabelCell(table, boldFont, "분석 일자");
        addValueCell(table, font, safe(site.getCreatedAt()));

        document.add(table);
    }

    private void addScoreTable(Document document, PdfFont font, PdfFont boldFont, DetailScores scores) {
        if (scores == null) {
            return;
        }
        Table table = new Table(UnitValue.createPercentArray(new float[]{28, 14, 58})).useAllAvailableWidth();
        addHeaderCell(table, boldFont, "평가 항목");
        addHeaderCell(table, boldFont, "점수");
        addHeaderCell(table, boldFont, "주요 산출 근거 및 요인");

        addScoreRow(table, font, "ML 기술적 적합도", scores.getMlTechnicalScore(), scores.getMlReason());
        addScoreRow(table, font, "Vision AI 환경 평가", scores.getVisionAiScore(), scores.getVisionReason());
        addScoreRow(table, font, "Rule-based 규제 검토", scores.getRuleBasedScore(), scores.getRuleReason());

        document.add(table);
    }

    private void addScoreRow(Table table, PdfFont font, String label, Integer score, String reason) {
        table.addCell(plainCell(font, label));
        table.addCell(plainCell(font, score != null ? score + "점 / 100점" : "-").setTextAlignment(TextAlignment.CENTER));
        table.addCell(plainCell(font, safe(reason)));
    }

    private void addXaiExplanation(Document document, PdfFont font, PdfFont boldFont, XaiExplanation xai) {
        if (xai == null) {
            return;
        }
        document.add(new Paragraph("주요 AI 판단 요약 (XAI)")
                .setFont(boldFont).setFontSize(10.5f).setMarginTop(8).setMarginBottom(4));
        addBulletList(document, font, boldFont, "가점 요인", xai.getBonusReason());
        addBulletList(document, font, boldFont, "감점 및 주의 요인", xai.getPenaltyReason());
    }

    private void addBulletList(Document document, PdfFont font, PdfFont boldFont, String label, List<String> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        document.add(new Paragraph(label + ":").setFont(boldFont).setFontSize(9.5f).setMarginBottom(2));
        for (String item : items) {
            document.add(new Paragraph("• " + item).setFont(font).setFontSize(9.5f).setMarginLeft(10).setMarginBottom(2));
        }
    }

    private void addVisionAnalysisTable(Document document, PdfFont font, PdfFont boldFont, String targetType,
                                         Map<String, Object> visionAnalysis) {
        Map<String, String> labels = TARGET_TYPE_ROOF.equalsIgnoreCase(targetType) ? roofVisionLabels() : landVisionLabels();

        Table table = new Table(UnitValue.createPercentArray(new float[]{35, 65})).useAllAvailableWidth();
        addHeaderCell(table, boldFont, "구분");
        addHeaderCell(table, boldFont, "분석 결과");

        for (Map.Entry<String, String> entry : labels.entrySet()) {
            Object value = visionAnalysis == null ? null : visionAnalysis.get(entry.getKey());
            table.addCell(plainCell(font, entry.getValue()));
            table.addCell(plainCell(font, formatVisionValue(entry.getKey(), value)));
        }

        document.add(table);
    }

    private LinkedHashMap<String, String> roofVisionLabels() {
        LinkedHashMap<String, String> labels = new LinkedHashMap<>();
        labels.put("roof_structure_type", "지붕 형태 및 구조");
        labels.put("roof_slope_deg", "지붕 경사도");
        labels.put("obstacle_shading_ratio_percent", "장애물 / 음영 비율");
        labels.put("obstacle_shading_area", "장애물 / 음영 면적");
        labels.put("recommended_orientation", "추천 모듈 방향");
        labels.put("recommended_tilt_angle_deg", "추천 설치 각도");
        return labels;
    }

    private LinkedHashMap<String, String> landVisionLabels() {
        LinkedHashMap<String, String> labels = new LinkedHashMap<>();
        labels.put("slope_degree", "지형 경사도");
        labels.put("aspect_direction", "부지 방위");
        labels.put("vegetation_coverage_percent", "식생 피복률");
        labels.put("has_access_road", "진입로 확보 여부");
        labels.put("access_road_width_m", "진입로 폭");
        labels.put("recommended_orientation", "추천 모듈 방향");
        labels.put("recommended_tilt_angle_deg", "추천 설치 각도");
        return labels;
    }

    private String formatVisionValue(String key, Object value) {
        if (value == null) {
            return "-";
        }
        if (value instanceof Boolean bool) {
            return bool ? "예" : "아니오";
        }
        String suffix = "";
        if (key.endsWith("_percent")) {
            suffix = "%";
        } else if (key.endsWith("_degree") || key.endsWith("_deg")) {
            suffix = "°";
        } else if (key.endsWith("_area")) {
            suffix = " m²";
        } else if (key.endsWith("_m")) {
            suffix = "m";
        }
        return value + suffix;
    }

    private void addSimulationTable(Document document, PdfFont font, PdfFont boldFont, Simulation sim) {
        if (sim == null) {
            return;
        }
        Table table = new Table(UnitValue.createPercentArray(new float[]{25, 25, 25, 25})).useAllAvailableWidth();
        addHeaderCell(table, boldFont, "추천 설치 용량");
        addHeaderCell(table, boldFont, "연간 예상 발전량");
        addHeaderCell(table, boldFont, "연간 예상 수익");
        addHeaderCell(table, boldFont, "투자 대비 수익률 (ROI)");

        table.addCell(plainCell(font, sim.getRecommendedCapacityKw() != null ? sim.getRecommendedCapacityKw() + " kW" : "-")
                .setTextAlignment(TextAlignment.CENTER));
        table.addCell(plainCell(font, sim.getAnnualGenerationKwh() != null
                        ? String.format("%,d kWh / 년", sim.getAnnualGenerationKwh()) : "-")
                .setTextAlignment(TextAlignment.CENTER));
        table.addCell(plainCell(font, sim.getAnnualRevenueKrw() != null
                        ? String.format("약 %,d 만원 / 년", sim.getAnnualRevenueKrw() / 10000) : "-")
                .setTextAlignment(TextAlignment.CENTER));

        String roiText = "-";
        if (sim.getRoiPercent() != null) {
            roiText = String.format("%.1f%%", sim.getRoiPercent());
            if (sim.getPaybackYears() != null) {
                roiText += String.format(" (회수기간 약 %.1f년)", sim.getPaybackYears());
            }
        }
        table.addCell(plainCell(font, roiText).setTextAlignment(TextAlignment.CENTER));

        document.add(table);
    }

    private void addRiskAndSupport(Document document, PdfFont font, PdfFont boldFont, RiskAndSupport riskAndSupport) {
        document.add(new Paragraph("규칙 기반(Rule-based) 종합 리스크 검토")
                .setFont(boldFont).setFontSize(10.5f).setMarginTop(4).setMarginBottom(4));

        RiskCheck risk = riskAndSupport.getRuleBasedRiskCheck();
        if (risk != null) {
            addLabeledLine(document, font, boldFont, "전력 계통 연계", risk.getGridConnection());
            addLabeledLine(document, font, boldFont, "조례 및 법적 규제", risk.getRegulation());
            addLabeledLine(document, font, boldFont, "주변 민원 가능성", risk.getPublicComplaint());
        }

        document.add(new Paragraph("추천 정부/지자체 연계 사업")
                .setFont(boldFont).setFontSize(10.5f).setMarginTop(10).setMarginBottom(4));
        List<String> subsidies = riskAndSupport.getRecommendedSubsidies();
        if (subsidies != null) {
            for (String subsidy : subsidies) {
                document.add(new Paragraph("• " + subsidy).setFont(font).setFontSize(9.5f).setMarginLeft(10).setMarginBottom(2));
            }
        }
    }

    private void addLabeledLine(Document document, PdfFont font, PdfFont boldFont, String label, String value) {
        document.add(new Paragraph()
                .add(new Text("• " + label + ": ").setFont(boldFont))
                .add(new Text(safe(value)).setFont(font))
                .setFontSize(9.5f)
                .setMarginBottom(2));
    }

    private void addChecklist(Document document, PdfFont font, PdfFont boldFont, List<ChecklistItem> items) {
        if (items == null || items.isEmpty()) {
            return;
        }
        Table table = new Table(UnitValue.createPercentArray(new float[]{8, 40, 52})).useAllAvailableWidth();
        addHeaderCell(table, boldFont, "확인");
        addHeaderCell(table, boldFont, "점검 항목");
        addHeaderCell(table, boldFont, "비고 / 세부 확인 사항");

        for (ChecklistItem item : items) {
            table.addCell(plainCell(font, "[ ]").setTextAlignment(TextAlignment.CENTER));
            table.addCell(plainCell(font, safe(item.getItem())));
            table.addCell(plainCell(font, safe(item.getNote())));
        }

        document.add(table);
    }

    private void addLabelCell(Table table, PdfFont boldFont, String text) {
        table.addCell(new Cell().add(new Paragraph(text).setFont(boldFont).setFontSize(9.5f))
                .setBackgroundColor(LIGHT_BLUE_BG)
                .setBorder(new SolidBorder(BORDER_GRAY, 0.5f))
                .setPadding(6));
    }

    private void addValueCell(Table table, PdfFont font, String text) {
        table.addCell(new Cell().add(new Paragraph(text).setFont(font).setFontSize(9.5f))
                .setBorder(new SolidBorder(BORDER_GRAY, 0.5f))
                .setPadding(6));
    }

    private void addHeaderCell(Table table, PdfFont boldFont, String text) {
        table.addCell(new Cell().add(new Paragraph(text).setFont(boldFont).setFontSize(9.5f))
                .setBackgroundColor(LIGHT_BLUE_BG)
                .setBorder(new SolidBorder(BORDER_GRAY, 0.5f))
                .setTextAlignment(TextAlignment.CENTER)
                .setPadding(6));
    }

    private Cell plainCell(PdfFont font, String text) {
        return new Cell().add(new Paragraph(text).setFont(font).setFontSize(9.5f))
                .setBorder(new SolidBorder(BORDER_GRAY, 0.5f))
                .setPadding(6);
    }

    private String formatArea(Double area) {
        return area != null ? String.format("%,.1f m²", area) : "-";
    }

    private String safe(String value) {
        return value != null ? value : "-";
    }
}
