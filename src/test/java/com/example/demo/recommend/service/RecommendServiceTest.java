package com.example.demo.recommend.service;

import com.example.demo.global.exception.CustomException;
import com.example.demo.global.exception.ErrorCode;
import com.example.demo.recommend.client.RecommendClient;
import com.example.demo.recommend.client.dto.JobResult;
import com.example.demo.recommend.client.dto.JobStatusResult;
import com.example.demo.recommend.client.dto.JobSubmitResult;
import com.example.demo.recommend.dto.RecommendationHistoryResponse;
import com.example.demo.recommend.dto.RecommendationStatusResponse;
import com.example.demo.recommend.dto.RecommendationSubmitResponse;
import com.example.demo.recommend.entity.JobStatus;
import com.example.demo.recommend.entity.RecommendationJob;
import com.example.demo.recommend.repository.RecommendationItemRepository;
import com.example.demo.recommend.repository.RecommendationJobRepository;
import com.example.demo.report.dto.AiAnalysisResponse;
import com.example.demo.user.entity.User;
import com.example.demo.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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

    @Mock
    private TransactionTemplate transactionTemplate;

    private RecommendService recommendService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        recommendService = new RecommendService(
                recommendClient, jobRepository, itemRepository, userRepository, JsonMapper.builder().build(), transactionTemplate
        );
        when(jobRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(new SimpleTransactionStatus());
        });
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

        recommendService.getStatus(1L, null);

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

        RecommendationStatusResponse response = recommendService.getStatus(1L, null);

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

        RecommendationStatusResponse response = recommendService.getStatus(1L, null);

        assertThat(job.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(response.errorMessage()).contains("재시작");
    }

    @Test
    void 폴링중_일시적_오류면_상태를_바꾸지_않는다() {
        RecommendationJob job = queuedJob();
        when(jobRepository.findById(1L)).thenReturn(Optional.of(job));
        when(recommendClient.pollJob("job-abc")).thenThrow(new CustomException(ErrorCode.AI_RECOMMEND_FAILED));

        RecommendationStatusResponse response = recommendService.getStatus(1L, null);

        assertThat(job.getStatus()).isEqualTo(JobStatus.QUEUED);
        assertThat(response.status()).isEqualTo("QUEUED");
        verify(jobRepository, never()).save(any());
    }

    @Test
    void 다른_사용자의_job을_조회하면_NOT_FOUND_예외를_던진다() {
        RecommendationJob job = RecommendationJob.builder()
                .id(1L)
                .externalJobId("job-abc")
                .originalFilename("sites.xlsx")
                .limitParam(3)
                .status(JobStatus.QUEUED)
                .user(User.builder().id(99L).build())
                .build();
        when(jobRepository.findById(1L)).thenReturn(Optional.of(job));

        assertThatThrownBy(() -> recommendService.getStatus(1L, 1L))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.RECOMMENDATION_JOB_NOT_FOUND);

        verify(recommendClient, never()).pollJob(any());
    }

    @Test
    void 소유자가_없는_job은_누구나_조회할_수_있다() {
        RecommendationJob job = queuedJob();
        job.markDone("{\"node0_parsed\":230}");
        when(jobRepository.findById(1L)).thenReturn(Optional.of(job));

        RecommendationStatusResponse response = recommendService.getStatus(1L, null);

        assertThat(response.status()).isEqualTo("DONE");
    }

    @Test
    void 소유자와_요청자가_같으면_조회할_수_있다() {
        RecommendationJob job = RecommendationJob.builder()
                .id(1L)
                .externalJobId("job-abc")
                .originalFilename("sites.xlsx")
                .limitParam(3)
                .status(JobStatus.QUEUED)
                .user(User.builder().id(5L).build())
                .build();
        job.markDone("{\"node0_parsed\":230}");
        when(jobRepository.findById(1L)).thenReturn(Optional.of(job));

        RecommendationStatusResponse response = recommendService.getStatus(1L, 5L);

        assertThat(response.status()).isEqualTo("DONE");
    }

    @Test
    void 폴링_결과의_status가_null이면_상태를_바꾸지_않는다() {
        RecommendationJob job = queuedJob();
        when(jobRepository.findById(1L)).thenReturn(Optional.of(job));

        JobStatusResult polled = new JobStatusResult();
        polled.setStatus(null);
        when(recommendClient.pollJob("job-abc")).thenReturn(polled);

        RecommendationStatusResponse response = recommendService.getStatus(1L, null);

        assertThat(job.getStatus()).isEqualTo(JobStatus.QUEUED);
        assertThat(response.status()).isEqualTo("QUEUED");
        verify(jobRepository, never()).save(any());
    }

    @Test
    void 폴링_결과가_done인데_result가_null이면_상태를_바꾸지_않는다() {
        RecommendationJob job = queuedJob();
        when(jobRepository.findById(1L)).thenReturn(Optional.of(job));

        JobStatusResult polled = new JobStatusResult();
        polled.setStatus("done");
        polled.setResult(null);
        when(recommendClient.pollJob("job-abc")).thenReturn(polled);

        RecommendationStatusResponse response = recommendService.getStatus(1L, null);

        assertThat(job.getStatus()).isEqualTo(JobStatus.QUEUED);
        assertThat(response.status()).isEqualTo("QUEUED");
        verify(jobRepository, never()).save(any());
    }

    @Test
    void getHistory는_리포지토리_결과를_요약_DTO로_매핑한다() {
        RecommendationJob doneJob = RecommendationJob.builder()
                .id(2L)
                .externalJobId("job-2")
                .originalFilename("완료파일.xlsx")
                .limitParam(3)
                .status(JobStatus.DONE)
                .build();
        ReflectionTestUtils.setField(doneJob, "createdAt", java.time.LocalDateTime.of(2026, 7, 28, 14, 0));

        RecommendationJob failedJob = RecommendationJob.builder()
                .id(1L)
                .externalJobId("job-1")
                .originalFilename("실패파일.xlsx")
                .limitParam(3)
                .status(JobStatus.FAILED)
                .errorMessage("AI 서버가 재시작되어 이전 작업 기록이 사라졌습니다. 파일을 다시 업로드해주세요.")
                .build();
        ReflectionTestUtils.setField(failedJob, "createdAt", java.time.LocalDateTime.of(2026, 7, 28, 13, 0));

        when(jobRepository.findTop10ByUser_IdOrderByCreatedAtDesc(5L)).thenReturn(List.of(doneJob, failedJob));

        List<RecommendationHistoryResponse> history = recommendService.getHistory(5L);

        assertThat(history).hasSize(2);
        assertThat(history.get(0).id()).isEqualTo(2L);
        assertThat(history.get(0).originalFilename()).isEqualTo("완료파일.xlsx");
        assertThat(history.get(0).status()).isEqualTo("DONE");
        assertThat(history.get(0).errorMessage()).isNull();
        assertThat(history.get(1).status()).isEqualTo("FAILED");
        assertThat(history.get(1).errorMessage()).contains("재시작");
    }

    @Test
    void 소유자가_삭제하면_item과_job이_모두_삭제된다() {
        RecommendationJob job = RecommendationJob.builder()
                .id(1L)
                .externalJobId("job-abc")
                .originalFilename("sites.xlsx")
                .limitParam(3)
                .status(JobStatus.DONE)
                .user(User.builder().id(5L).build())
                .build();
        when(jobRepository.findById(1L)).thenReturn(Optional.of(job));

        recommendService.delete(1L, 5L);

        verify(itemRepository).deleteByJob(job);
        verify(jobRepository).delete(job);
    }

    @Test
    void 소유자가_아니면_NOT_FOUND_예외를_던지고_삭제하지_않는다() {
        RecommendationJob job = RecommendationJob.builder()
                .id(1L)
                .externalJobId("job-abc")
                .originalFilename("sites.xlsx")
                .limitParam(3)
                .status(JobStatus.DONE)
                .user(User.builder().id(99L).build())
                .build();
        when(jobRepository.findById(1L)).thenReturn(Optional.of(job));

        assertThatThrownBy(() -> recommendService.delete(1L, 5L))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.RECOMMENDATION_JOB_NOT_FOUND);

        verify(itemRepository, never()).deleteByJob(any());
        verify(jobRepository, never()).delete(any());
    }

    @Test
    void 익명_job은_비로그인_요청자도_삭제할_수_있다() {
        RecommendationJob job = queuedJob();
        when(jobRepository.findById(1L)).thenReturn(Optional.of(job));

        recommendService.delete(1L, null);

        verify(itemRepository).deleteByJob(job);
        verify(jobRepository).delete(job);
    }

    @Test
    void 존재하지_않는_job을_삭제하면_NOT_FOUND_예외를_던진다() {
        when(jobRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> recommendService.delete(1L, 5L))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.RECOMMENDATION_JOB_NOT_FOUND);
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
