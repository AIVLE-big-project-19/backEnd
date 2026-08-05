package com.example.demo.analysis.service;

import com.example.demo.analysis.entity.AnalysisSnapshot;
import com.example.demo.analysis.repository.AnalysisSnapshotRepository;
import com.example.demo.dashboard.dto.DashboardCandidateAnalysisResponse;
import com.example.demo.global.exception.CustomException;
import com.example.demo.global.exception.ErrorCode;
import com.example.demo.idleland.entity.IdleLand;
import com.example.demo.report.dto.AiAnalysisResponse;
import com.example.demo.report.service.ReportService;
import com.example.demo.user.entity.User;
import com.example.demo.user.repository.UserRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AnalysisSnapshotService {

    private final AnalysisSnapshotRepository snapshotRepository;
    private final UserRepository userRepository;
    private final ReportService reportService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Transactional
    public Long save(Long userId, IdleLand idleLand, AiAnalysisResponse analysis,
                     DashboardCandidateAnalysisResponse dashboardResponse) {
        if (userId == null) {
            return null;
        }

        try {
            User user = userRepository.findById(userId)
                    .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
            AnalysisSnapshot snapshot = AnalysisSnapshot.builder()
                    .user(user)
                    .sourceId(idleLand.getSourceId())
                    .candidateId(idleLand.getId())
                    .address(idleLand.getAddress())
                    .siteType(idleLand.getAssetTypeNorm())
                    .latitude(idleLand.getLatitude())
                    .longitude(idleLand.getLongitude())
                    .analysisJson(objectMapper.writeValueAsString(analysis))
                    .responseJson(objectMapper.writeValueAsString(dashboardResponse))
                    .favorite(false)
                    .status("REVIEWING")
                    .build();
            return snapshotRepository.save(snapshot).getId();
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("분석 결과를 저장할 수 없습니다.", exception);
        }
    }

    @Transactional(readOnly = true)
    public List<AnalysisHistoryItem> history(Long userId) {
        return snapshotRepository.findAllByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(this::toHistoryItem)
                .toList();
    }

    @Transactional
    public void updateManagement(Long snapshotId, Long userId, boolean favorite, String status) {
        AnalysisSnapshot snapshot = findOwned(snapshotId, userId);
        snapshot.updateManagement(favorite, status);
    }

    @Transactional
    public void delete(Long snapshotId, Long userId) {
        snapshotRepository.delete(findOwned(snapshotId, userId));
    }

    private AnalysisSnapshot findOwned(Long snapshotId, Long userId) {
        AnalysisSnapshot snapshot = snapshotRepository.findById(snapshotId)
                .orElseThrow(() -> new CustomException(ErrorCode.IDLE_LAND_NOT_FOUND));
        if (snapshot.getUser() == null || !snapshot.getUser().getId().equals(userId)) {
            throw new CustomException(ErrorCode.BOARD_ACCESS_DENIED);
        }
        return snapshot;
    }

    private AnalysisHistoryItem toHistoryItem(AnalysisSnapshot snapshot) {
        try {
            DashboardCandidateAnalysisResponse analysis = objectMapper.readValue(
                    snapshot.getResponseJson(), DashboardCandidateAnalysisResponse.class);
            return new AnalysisHistoryItem(
                    snapshot.getId(), snapshot.getCandidateId(), snapshot.getSourceId(), snapshot.getAddress(),
                    snapshot.getSiteType(), snapshot.getLatitude(), snapshot.getLongitude(), analysis,
                    snapshot.getCreatedAt(), snapshot.isFavorite(), snapshot.getStatus());
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("저장된 분석 이력을 읽을 수 없습니다.", exception);
        }
    }

    public record AnalysisHistoryItem(
            Long analysisId,
            Long candidateId,
            String sourceId,
            String address,
            String siteType,
            Double latitude,
            Double longitude,
            DashboardCandidateAnalysisResponse analysis,
            java.time.LocalDateTime analyzedAt,
            boolean favorite,
            String status
    ) {}

    @Transactional
    public byte[] getOrCreatePdf(Long snapshotId, Long userId) throws IOException {
        AnalysisSnapshot snapshot = snapshotRepository.findById(snapshotId)
                .orElseThrow(() -> new CustomException(ErrorCode.IDLE_LAND_NOT_FOUND));
        if (snapshot.getUser() != null && !snapshot.getUser().getId().equals(userId)) {
            throw new CustomException(ErrorCode.BOARD_ACCESS_DENIED);
        }
        if (snapshot.getPdfBytes() != null && snapshot.getPdfBytes().length > 0) {
            return snapshot.getPdfBytes();
        }

        AiAnalysisResponse analysis;
        try {
            analysis = objectMapper.readValue(snapshot.getAnalysisJson(), AiAnalysisResponse.class);
        } catch (JsonProcessingException exception) {
            throw new IOException("저장된 분석 결과를 읽을 수 없습니다.", exception);
        }
        byte[] pdf = reportService.generateReportPdf(analysis);
        snapshot.cachePdf(pdf);
        snapshotRepository.save(snapshot);
        return pdf;
    }
}
