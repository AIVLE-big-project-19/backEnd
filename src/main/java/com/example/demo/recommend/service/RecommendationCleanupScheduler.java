package com.example.demo.recommend.service;

import com.example.demo.recommend.entity.RecommendationJob;
import com.example.demo.recommend.repository.RecommendationItemRepository;
import com.example.demo.recommend.repository.RecommendationJobRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Component
public class RecommendationCleanupScheduler {

    private static final Logger log = LoggerFactory.getLogger(RecommendationCleanupScheduler.class);

    private static final long RETENTION_HOURS = 24;

    private final RecommendationJobRepository jobRepository;
    private final RecommendationItemRepository itemRepository;

    public RecommendationCleanupScheduler(
            RecommendationJobRepository jobRepository,
            RecommendationItemRepository itemRepository
    ) {
        this.jobRepository = jobRepository;
        this.itemRepository = itemRepository;
    }

    @Scheduled(fixedRate = 3_600_000)
    @Transactional
    public void cleanupExpiredAnonymousJobs() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(RETENTION_HOURS);
        List<RecommendationJob> expired = jobRepository.findByUserIsNullAndCreatedAtBefore(cutoff);
        if (expired.isEmpty()) {
            return;
        }
        itemRepository.deleteByJobIn(expired);
        jobRepository.deleteAll(expired);
        log.info("만료된 익명 job {}건 정리", expired.size());
    }
}
