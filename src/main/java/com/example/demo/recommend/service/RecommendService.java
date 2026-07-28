package com.example.demo.recommend.service;

import com.example.demo.global.exception.CustomException;
import com.example.demo.global.exception.ErrorCode;
import com.example.demo.recommend.client.RecommendClient;
import com.example.demo.recommend.client.dto.JobResult;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * NOTE on transaction boundaries: the calls out to {@link RecommendClient} are network round-trips
 * (bounded by the configured HTTP timeout) and must never run inside an open DB transaction, or every
 * poll would hold a pooled connection for the duration of the call and exhaust the pool under
 * concurrent polling. Every DB read/write in this class is therefore wrapped in its own short-lived
 * {@link TransactionTemplate#execute} block, with the {@link RecommendClient} call happening in
 * between, outside of any transaction. (Plain {@code @Transactional} on the public methods would not
 * achieve this: it would wrap the network call together with the DB access in one transaction, and
 * splitting into private helper methods would not help either, since self-invocation on {@code this}
 * bypasses the Spring AOP transaction proxy entirely.)
 */
@Service
public class RecommendService {

    private static final Logger log = LoggerFactory.getLogger(RecommendService.class);

    private final RecommendClient recommendClient;
    private final RecommendationJobRepository jobRepository;
    private final RecommendationItemRepository itemRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public RecommendService(
            RecommendClient recommendClient,
            RecommendationJobRepository jobRepository,
            RecommendationItemRepository itemRepository,
            UserRepository userRepository,
            ObjectMapper objectMapper,
            TransactionTemplate transactionTemplate
    ) {
        this.recommendClient = recommendClient;
        this.jobRepository = jobRepository;
        this.itemRepository = itemRepository;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
    }

    public RecommendationSubmitResponse submit(MultipartFile file, int limit, Long userId) {
        // TEMP DIAGNOSTIC — remove after root-causing the AI_RECOMMEND_FAILED / "file field required" report.
        log.warn("[DIAG] incoming MultipartFile: originalFilename={}, size={}, contentType={}, isEmpty={}",
                file.getOriginalFilename(), file.getSize(), file.getContentType(), file.isEmpty());

        // Network call first, outside of any transaction.
        JobSubmitResult submitResult = recommendClient.submitJob(file, limit);

        // DB read + write happen in a short transaction after the network call has completed.
        RecommendationJob job = transactionTemplate.execute(status -> {
            User user = userId == null ? null : userRepository.findById(userId).orElse(null);

            return jobRepository.save(RecommendationJob.builder()
                    .externalJobId(submitResult.getJobId())
                    .user(user)
                    .originalFilename(file.getOriginalFilename())
                    .limitParam(limit)
                    .status(JobStatus.QUEUED)
                    .build());
        });

        return new RecommendationSubmitResponse(job.getId(), job.getStatus().name());
    }

    public RecommendationStatusResponse getStatus(Long jobId, Long requesterUserId) {
        // Load the job (and resolve the lazy owner association, needed for the ownership check
        // below) inside a short transaction so the Hibernate session is still open.
        RecommendationJob job = transactionTemplate.execute(status -> {
            RecommendationJob found = jobRepository.findById(jobId)
                    .orElseThrow(() -> new CustomException(ErrorCode.RECOMMENDATION_JOB_NOT_FOUND));

            if (found.getUser() != null && !found.getUser().getId().equals(requesterUserId)) {
                // Same "not found" error as a missing id: avoids confirming to a probing caller
                // that the id exists but belongs to someone else.
                throw new CustomException(ErrorCode.RECOMMENDATION_JOB_NOT_FOUND);
            }

            return found;
        });

        if (job.getStatus() == JobStatus.QUEUED || job.getStatus() == JobStatus.RUNNING) {
            return refreshFromAiServer(job);
        }

        return transactionTemplate.execute(status -> toResponse(job, null));
    }

    private RecommendationStatusResponse refreshFromAiServer(RecommendationJob job) {
        JobStatusResult polled;
        try {
            // Network call, outside of any transaction.
            polled = recommendClient.pollJob(job.getExternalJobId());
        } catch (CustomException e) {
            log.warn("AI 서버 폴링 중 일시적 오류, 마지막 상태 유지: jobId={}", job.getId(), e);
            return transactionTemplate.execute(status -> toResponse(job, null));
        }

        if (polled == null) {
            return transactionTemplate.execute(status -> {
                job.markFailed("AI 서버가 재시작되어 이전 작업 기록이 사라졌습니다. 파일을 다시 업로드해주세요.");
                jobRepository.save(job);
                return toResponse(job, null);
            });
        }

        String polledStatus = polled.getStatus();
        if (polledStatus == null) {
            log.warn("AI 서버 폴링 응답에 status가 없음, 마지막 상태 유지: jobId={}", job.getId());
            return transactionTemplate.execute(status -> toResponse(job, null));
        }

        return switch (polledStatus) {
            case "done" -> handleDone(job, polled.getResult());
            case "failed" -> transactionTemplate.execute(status -> {
                job.markFailed(polled.getError());
                jobRepository.save(job);
                return toResponse(job, null);
            });
            case "running" -> transactionTemplate.execute(status -> {
                job.markRunning(polled.getStage());
                jobRepository.save(job);
                return toResponse(job, null);
            });
            default -> transactionTemplate.execute(status -> toResponse(job, null)); // "queued": 상태 변화 없음
        };
    }

    private RecommendationStatusResponse handleDone(RecommendationJob job, JobResult result) {
        if (result == null) {
            log.warn("AI 서버 폴링 응답이 done인데 result가 없음, 마지막 상태 유지: jobId={}", job.getId());
            return transactionTemplate.execute(status -> toResponse(job, null));
        }

        List<AiAnalysisResponse> recommendations = result.getRecommendations();
        return transactionTemplate.execute(status -> {
            job.markDone(objectMapper.writeValueAsString(result.getFunnel()));
            saveItems(job, recommendations);
            jobRepository.save(job);
            return toResponse(job, recommendations);
        });
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
                    : itemRepository.findByJobOrderById(job).stream()
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
