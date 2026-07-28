package com.example.demo.recommend.service;

import com.example.demo.global.exception.CustomException;
import com.example.demo.global.exception.ErrorCode;
import com.example.demo.recommend.client.RecommendClient;
import com.example.demo.recommend.client.dto.JobStatusResult;
import com.example.demo.recommend.client.dto.JobSubmitResult;
import com.example.demo.recommend.dto.RecommendationStatusResponse;
import com.example.demo.recommend.dto.RecommendationSubmitResponse;
import com.example.demo.recommend.entity.JobStatus;
import com.example.demo.recommend.entity.RecommendationItem;
import com.example.demo.recommend.entity.RecommendationJob;
import com.example.demo.recommend.repository.RecommendationItemRepository;
import com.example.demo.recommend.repository.RecommendationJobRepository;
import com.example.demo.report.dto.AiAnalysisResponse;
import com.example.demo.report.dto.ScoresAndEvaluation;
import com.example.demo.report.dto.SiteInfo;
import com.example.demo.user.entity.User;
import com.example.demo.user.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

@Service
public class RecommendService {

    private static final Logger log = LoggerFactory.getLogger(RecommendService.class);

    private final RecommendClient recommendClient;
    private final RecommendationJobRepository jobRepository;
    private final RecommendationItemRepository itemRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    public RecommendService(
            RecommendClient recommendClient,
            RecommendationJobRepository jobRepository,
            RecommendationItemRepository itemRepository,
            UserRepository userRepository,
            ObjectMapper objectMapper
    ) {
        this.recommendClient = recommendClient;
        this.jobRepository = jobRepository;
        this.itemRepository = itemRepository;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public RecommendationSubmitResponse submit(MultipartFile file, int limit, Long userId) {
        JobSubmitResult submitResult = recommendClient.submitJob(file, limit);
        User user = userId == null ? null : userRepository.findById(userId).orElse(null);

        RecommendationJob job = jobRepository.save(RecommendationJob.builder()
                .externalJobId(submitResult.getJobId())
                .user(user)
                .originalFilename(file.getOriginalFilename())
                .limitParam(limit)
                .status(JobStatus.QUEUED)
                .build());

        return new RecommendationSubmitResponse(job.getId(), job.getStatus().name());
    }

    @Transactional
    public RecommendationStatusResponse getStatus(Long jobId) {
        RecommendationJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new CustomException(ErrorCode.RECOMMENDATION_JOB_NOT_FOUND));

        if (job.getStatus() == JobStatus.QUEUED || job.getStatus() == JobStatus.RUNNING) {
            return refreshFromAiServer(job);
        }

        return toResponse(job, null);
    }

    private RecommendationStatusResponse refreshFromAiServer(RecommendationJob job) {
        JobStatusResult polled;
        try {
            polled = recommendClient.pollJob(job.getExternalJobId());
        } catch (CustomException e) {
            log.warn("AI 서버 폴링 중 일시적 오류, 마지막 상태 유지: jobId={}", job.getId(), e);
            return toResponse(job, null);
        }

        if (polled == null) {
            job.markFailed("AI 서버가 재시작되어 이전 작업 기록이 사라졌습니다. 파일을 다시 업로드해주세요.");
            jobRepository.save(job);
            return toResponse(job, null);
        }

        switch (polled.getStatus()) {
            case "done" -> {
                List<AiAnalysisResponse> recommendations = polled.getResult().getRecommendations();
                job.markDone(objectMapper.writeValueAsString(polled.getResult().getFunnel()));
                saveItems(job, recommendations);
                jobRepository.save(job);
                return toResponse(job, recommendations);
            }
            case "failed" -> {
                job.markFailed(polled.getError());
                jobRepository.save(job);
                return toResponse(job, null);
            }
            case "running" -> {
                job.markRunning(polled.getStage());
                jobRepository.save(job);
                return toResponse(job, null);
            }
            default -> {
                return toResponse(job, null); // "queued": 상태 변화 없음
            }
        }
    }

    private void saveItems(RecommendationJob job, List<AiAnalysisResponse> recommendations) {
        if (recommendations == null) {
            return;
        }
        List<RecommendationItem> items = recommendations.stream()
                .map(item -> toItem(job, item))
                .toList();
        itemRepository.saveAll(items);
    }

    private RecommendationItem toItem(RecommendationJob job, AiAnalysisResponse item) {
        SiteInfo siteInfo = item.getSiteInfo();
        ScoresAndEvaluation scores = item.getScoresAndEvaluation();

        return RecommendationItem.builder()
                .job(job)
                .targetType(item.getTargetType())
                .siteId(siteInfo != null ? siteInfo.getSiteId() : null)
                .address(siteInfo != null ? siteInfo.getAddress() : null)
                .grade(scores != null ? scores.getGrade() : null)
                .totalScore(scores != null ? scores.getTotalScore() : null)
                .priorityRank(scores != null ? scores.getPriorityRank() : null)
                .status(scores != null ? scores.getStatus() : null)
                .payload(objectMapper.writeValueAsString(item))
                .build();
    }

    private RecommendationStatusResponse toResponse(RecommendationJob job, List<AiAnalysisResponse> freshRecommendations) {
        Map<String, Object> funnel = null;
        List<AiAnalysisResponse> recommendations = null;

        if (job.getStatus() == JobStatus.DONE) {
            funnel = readFunnel(job.getFunnelJson());
            recommendations = freshRecommendations != null
                    ? freshRecommendations
                    : itemRepository.findByJob(job).stream()
                            .map(this::readPayload)
                            .toList();
        }

        return new RecommendationStatusResponse(
                job.getId(),
                job.getStatus().name(),
                job.getStage(),
                funnel,
                recommendations,
                job.getErrorMessage()
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readFunnel(String funnelJson) {
        return funnelJson == null ? null : objectMapper.readValue(funnelJson, Map.class);
    }

    private AiAnalysisResponse readPayload(RecommendationItem item) {
        return objectMapper.readValue(item.getPayload(), AiAnalysisResponse.class);
    }
}
