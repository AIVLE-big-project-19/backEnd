package com.example.demo.recommend.service;

import com.example.demo.recommend.entity.JobStatus;
import com.example.demo.recommend.entity.RecommendationJob;
import com.example.demo.recommend.repository.RecommendationItemRepository;
import com.example.demo.recommend.repository.RecommendationJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecommendationCleanupSchedulerTest {

    @Mock
    private RecommendationJobRepository jobRepository;

    @Mock
    private RecommendationItemRepository itemRepository;

    private RecommendationCleanupScheduler scheduler;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        scheduler = new RecommendationCleanupScheduler(jobRepository, itemRepository);
    }

    @Test
    void 정리대상이_없으면_삭제를_호출하지_않는다() {
        when(jobRepository.findByUserIsNullAndCreatedAtBefore(any())).thenReturn(List.of());

        scheduler.cleanupExpiredAnonymousJobs();

        verify(itemRepository, never()).deleteByJobIn(any());
        verify(jobRepository, never()).deleteAll(anyList());
    }

    @Test
    void 정리대상이_있으면_item과_job을_순서대로_삭제한다() {
        RecommendationJob job1 = RecommendationJob.builder()
                .id(1L).externalJobId("job-1").originalFilename("a.xlsx")
                .limitParam(3).status(JobStatus.QUEUED).build();
        RecommendationJob job2 = RecommendationJob.builder()
                .id(2L).externalJobId("job-2").originalFilename("b.xlsx")
                .limitParam(3).status(JobStatus.QUEUED).build();
        List<RecommendationJob> expired = List.of(job1, job2);
        when(jobRepository.findByUserIsNullAndCreatedAtBefore(any())).thenReturn(expired);

        scheduler.cleanupExpiredAnonymousJobs();

        verify(itemRepository).deleteByJobIn(expired);
        verify(jobRepository).deleteAll(expired);
    }
}
