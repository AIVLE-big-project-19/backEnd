package com.example.demo.idleland.service;

import com.example.demo.global.exception.CustomException;
import com.example.demo.global.exception.ErrorCode;
import com.example.demo.idleland.client.MlScoringClient;
import com.example.demo.idleland.dto.MlRankResponse;
import com.example.demo.idleland.entity.IdleLand;
import com.example.demo.idleland.repository.IdleLandRepository;
import com.example.demo.report.dto.AiAnalysisResponse;
import com.example.demo.report.service.ReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;

// 검색으로 찾은 유휴부지 후보 1건에 대해 ML을 다시 호출해(SHAP 포함) 기존 PDF 보고서를 생성한다.
@Service
@RequiredArgsConstructor
public class IdleLandReportService {

    private final IdleLandRepository idleLandRepository;
    private final MlScoringClient mlScoringClient;
    private final ReportService reportService;

    public byte[] generateReportPdf(Long idleLandId) throws IOException {
        IdleLand idleLand = idleLandRepository.findById(idleLandId)
                .orElseThrow(() -> new CustomException(ErrorCode.IDLE_LAND_NOT_FOUND));

        String datasetType = "BUILDING".equals(idleLand.getAssetTypeNorm()) ? "building" : "land";

        MlRankResponse response = mlScoringClient.rank(datasetType, List.of(idleLand), 1, true);
        List<AiAnalysisResponse> topCandidates = response.getTopCandidates();
        if (topCandidates == null || topCandidates.isEmpty()) {
            throw new CustomException(ErrorCode.ML_SERVER_REQUEST_FAILED, "ML 서버가 분석 결과를 반환하지 않았습니다.");
        }

        return reportService.generateReportPdf(topCandidates.get(0));
    }
}
