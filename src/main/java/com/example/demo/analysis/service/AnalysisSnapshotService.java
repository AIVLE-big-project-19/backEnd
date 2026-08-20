package com.example.demo.analysis.service;

import com.example.demo.analysis.entity.AnalysisSnapshot;
import com.example.demo.analysis.repository.AnalysisSnapshotRepository;
import com.example.demo.dashboard.dto.DashboardCandidateAnalysisResponse;
import com.example.demo.global.exception.CustomException;
import com.example.demo.global.exception.ErrorCode;
import com.example.demo.idleland.entity.IdleLand;
import com.example.demo.idleland.repository.IdleLandRepository;
import com.example.demo.idleland.service.VisionEnrichmentService;
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
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class AnalysisSnapshotService {

    private final AnalysisSnapshotRepository snapshotRepository;
    private final UserRepository userRepository;
    private final IdleLandRepository idleLandRepository;
    private final ReportService reportService;
    private final VisionEnrichmentService visionEnrichmentService;
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
            var evaluation = analysis.getScoresAndEvaluation();
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
                    .totalScore(evaluation == null ? null : evaluation.getTotalScore())
                    .grade(evaluation == null ? null : evaluation.getGrade())
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

    @Transactional(readOnly = true)
    public List<AdminAnalysisLogItem> adminHistory() {
        return snapshotRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(snapshot -> new AdminAnalysisLogItem(
                        snapshot.getId(),
                        snapshot.getUser() == null ? null : snapshot.getUser().getLoginId(),
                        snapshot.getAddress(),
                        snapshot.getSiteType(),
                        snapshot.getTotalScore(),
                        snapshot.getGrade(),
                        snapshot.getCreatedAt(),
                        snapshot.isFavorite(),
                        snapshot.getStatus()
                ))
                .toList();
    }

    public record AdminAnalysisLogItem(
            Long analysisId,
            String loginId,
            String address,
            String siteType,
            Integer totalScore,
            String grade,
            java.time.LocalDateTime analyzedAt,
            boolean favorite,
            String status
    ) {}

    @Transactional
    public void updateManagement(Long snapshotId, Long userId, boolean favorite, String status) {
        AnalysisSnapshot snapshot = findOwned(snapshotId, userId);
        snapshot.updateManagement(favorite, status);
    }

    @Transactional
    public void delete(Long snapshotId, Long userId) {
        snapshotRepository.delete(findOwned(snapshotId, userId));
    }

    @Transactional
    public void deleteSelected(List<Long> snapshotIds, Long userId) {
        List<Long> ids = snapshotIds == null ? List.of() : snapshotIds.stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return;
        }

        List<AnalysisSnapshot> snapshots = snapshotRepository.findAllByIdInAndUserId(ids, userId);
        if (snapshots.size() != ids.size()) {
            throw new CustomException(ErrorCode.BOARD_ACCESS_DENIED);
        }
        snapshotRepository.deleteAll(snapshots);
    }

    @Transactional
    public void deleteAll(Long userId) {
        snapshotRepository.deleteAllByUserId(userId);
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

        AiAnalysisResponse analysis;
        try {
            analysis = objectMapper.readValue(snapshot.getAnalysisJson(), AiAnalysisResponse.class);
        } catch (JsonProcessingException exception) {
            throw new IOException("저장된 분석 결과를 읽을 수 없습니다.", exception);
        }

        idleLandRepository.findById(snapshot.getCandidateId())
                .ifPresent(idleLand -> visionEnrichmentService.enrich(idleLand, analysis));

        return reportService.generateReportPdf(analysis);
    }
}
