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
import com.example.demo.recommend.entity.RecommendationJob;
import com.example.demo.recommend.repository.RecommendationItemRepository;
import com.example.demo.recommend.repository.RecommendationJobRepository;
import com.example.demo.report.dto.AiAnalysisResponse;
import com.example.demo.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecommendServiceTest {

    @Mock
    private RecommendClient recommendClient;

    @Mock
    private RecommendationJobRepository jobRepository;

    @Mock
    private RecommendationItemRepository itemRepository;

    @Mock
    private UserRepository userRepository;

    private RecommendService recommendService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        recommendService = new RecommendService(
                recommendClient, jobRepository, itemRepository, userRepository, JsonMapper.builder().build()
        );
        when(jobRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void 등록하면_QUEUED_상태로_job을_저장한다() {
        MockMultipartFile file = new MockMultipartFile("file", "sites.xlsx", "application/vnd.ms-excel", "dummy".getBytes());
        JobSubmitResult submitResult = new JobSubmitResult();
        submitResult.setJobId("job-abc");
        when(recommendClient.submitJob(file, 3)).thenReturn(submitResult);

        RecommendationSubmitResponse response = recommendService.submit(file, 3, null);

        assertThat(response.status()).isEqualTo("QUEUED");
        ArgumentCaptor<RecommendationJob> captor = ArgumentCaptor.forClass(RecommendationJob.class);
        verify(jobRepository).save(captor.capture());
        assertThat(captor.getValue().getExternalJobId()).isEqualTo("job-abc");
        assertThat(captor.getValue().getOriginalFilename()).isEqualTo("sites.xlsx");
        assertThat(captor.getValue().getLimitParam()).isEqualTo(3);
        assertThat(captor.getValue().getStatus()).isEqualTo(JobStatus.QUEUED);
        verify(userRepository, never()).findById(any());
    }

    @Test
    void 이미_완료된_job은_AI_서버를_다시_호출하지_않는다() {
        RecommendationJob job = queuedJob();
        job.markDone("{\"node0_parsed\":230}");
        when(jobRepository.findById(1L)).thenReturn(Optional.of(job));

        recommendService.getStatus(1L);

        verify(recommendClient, never()).pollJob(any());
    }

    @Test
    void 폴링_결과가_done이면_아이템을_저장하고_상태를_DONE으로_바꾼다() {
        RecommendationJob job = queuedJob();
        when(jobRepository.findById(1L)).thenReturn(Optional.of(job));

        JobStatusResult polled = new JobStatusResult();
        polled.setStatus("done");
        JobResult jobResult = new JobResult();
        jobResult.setFunnel(Map.of("node0_parsed", 230));
        jobResult.setRecommendations(List.of(new AiAnalysisResponse()));
        polled.setResult(jobResult);
        when(recommendClient.pollJob("job-abc")).thenReturn(polled);

        RecommendationStatusResponse response = recommendService.getStatus(1L);

        assertThat(job.getStatus()).isEqualTo(JobStatus.DONE);
        assertThat(response.status()).isEqualTo("DONE");
        assertThat(response.funnel()).containsEntry("node0_parsed", 230);
        assertThat(response.recommendations()).hasSize(1);
        verify(itemRepository).saveAll(any());
    }

    @Test
    void 폴링_결과가_404면_job을_FAILED로_바꾼다() {
        RecommendationJob job = queuedJob();
        when(jobRepository.findById(1L)).thenReturn(Optional.of(job));
        when(recommendClient.pollJob("job-abc")).thenReturn(null);

        RecommendationStatusResponse response = recommendService.getStatus(1L);

        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(response.errorMessage()).contains("재시작");
    }

    @Test
    void 폴링중_일시적_오류면_상태를_바꾸지_않는다() {
        RecommendationJob job = queuedJob();
        when(jobRepository.findById(1L)).thenReturn(Optional.of(job));
        when(recommendClient.pollJob("job-abc")).thenThrow(new CustomException(ErrorCode.AI_RECOMMEND_FAILED));

        RecommendationStatusResponse response = recommendService.getStatus(1L);

        assertThat(job.getStatus()).isEqualTo(JobStatus.QUEUED);
        assertThat(response.status()).isEqualTo("QUEUED");
        verify(jobRepository, never()).save(any());
    }

    private RecommendationJob queuedJob() {
        return RecommendationJob.builder()
                .id(1L)
                .externalJobId("job-abc")
                .originalFilename("sites.xlsx")
                .limitParam(3)
                .status(JobStatus.QUEUED)
                .build();
    }
}
