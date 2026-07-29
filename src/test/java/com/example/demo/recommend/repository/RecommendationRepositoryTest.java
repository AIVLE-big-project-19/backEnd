package com.example.demo.recommend.repository;

import com.example.demo.global.config.JpaAuditingConfig;
import com.example.demo.recommend.entity.JobStatus;
import com.example.demo.recommend.entity.RecommendationItem;
import com.example.demo.recommend.entity.RecommendationJob;
import com.example.demo.user.entity.Provider;
import com.example.demo.user.entity.Role;
import com.example.demo.user.entity.User;
import com.example.demo.user.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never"
})
@Import(JpaAuditingConfig.class)
class RecommendationRepositoryTest {

    @Autowired
    private RecommendationJobRepository jobRepository;

    @Autowired
    private RecommendationItemRepository itemRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    @Test
    void job과_item을_저장하고_JSON_컬럼을_그대로_재조회한다() {
        String funnelJson = "{\"node0_parsed\":230,\"node1_after_rule_filter\":121}";

        RecommendationJob job = jobRepository.save(RecommendationJob.builder()
                .externalJobId("job-abc")
                .originalFilename("대전광역시_유휴공간.xlsx")
                .limitParam(3)
                .status(JobStatus.QUEUED)
                .build());

        job.markDone(funnelJson);
        jobRepository.save(job);

        String payloadJson = "{\"target_type\":\"LAND\",\"1_site_info\":{\"site_id\":\"SITE_00042\"}}";

        RecommendationItem item = itemRepository.save(RecommendationItem.builder()
                .job(job)
                .targetType("LAND")
                .siteId("SITE_00042")
                .address("충청남도 ○○군 ○○리 12-3")
                .grade("A")
                .totalScore(87)
                .priorityRank("1")
                .status("통과")
                .payload(payloadJson)
                .build());

        RecommendationJob reloadedJob = jobRepository.findById(job.getId()).orElseThrow();
        assertThat(reloadedJob.getStatus()).isEqualTo(JobStatus.DONE);
        assertThat(reloadedJob.getFunnelJson()).isEqualTo(funnelJson);

        RecommendationItem reloadedItem = itemRepository.findById(item.getId()).orElseThrow();
        assertThat(reloadedItem.getPayload()).isEqualTo(payloadJson);
        assertThat(reloadedItem.getJob().getId()).isEqualTo(job.getId());

        assertThat(itemRepository.findByJobOrderById(job)).containsExactly(reloadedItem);
    }

    @Test
    void user가_없어도_job을_저장할_수_있다() {
        RecommendationJob job = jobRepository.save(RecommendationJob.builder()
                .externalJobId("job-anon")
                .originalFilename("파일.xlsx")
                .limitParam(0)
                .status(JobStatus.QUEUED)
                .build());

        assertThat(jobRepository.findById(job.getId()).orElseThrow().getUser()).isNull();
    }

    @Test
    void 사용자별_최근_10건만_생성일_내림차순으로_조회한다() {
        User owner = userRepository.save(User.builder()
                .loginId("owner01")
                .email("owner01@example.com")
                .password("hashed")
                .name("소유자")
                .provider(Provider.LOCAL)
                .role(Role.USER)
                .build());

        User other = userRepository.save(User.builder()
                .loginId("other01")
                .email("other01@example.com")
                .password("hashed")
                .name("다른사람")
                .provider(Provider.LOCAL)
                .role(Role.USER)
                .build());

        jobRepository.save(RecommendationJob.builder()
                .externalJobId("job-other")
                .user(other)
                .originalFilename("남의파일.xlsx")
                .limitParam(3)
                .status(JobStatus.QUEUED)
                .build());

        for (int i = 0; i < 11; i++) {
            jobRepository.save(RecommendationJob.builder()
                    .externalJobId("job-" + i)
                    .user(owner)
                    .originalFilename("파일" + i + ".xlsx")
                    .limitParam(3)
                    .status(JobStatus.QUEUED)
                    .build());
        }

        List<RecommendationJob> history = jobRepository.findTop10ByUser_IdOrderByCreatedAtDesc(owner.getId());

        assertThat(history).hasSize(10);
        assertThat(history).allMatch(job -> job.getUser().getId().equals(owner.getId()));
        assertThat(history.get(0).getExternalJobId()).isEqualTo("job-10");
    }

    @Test
    void deleteByJob으로_item을_지우면_그_다음_job도_삭제할_수_있다() {
        RecommendationJob job = jobRepository.save(RecommendationJob.builder()
                .externalJobId("job-del")
                .originalFilename("삭제될파일.xlsx")
                .limitParam(3)
                .status(JobStatus.QUEUED)
                .build());

        itemRepository.save(RecommendationItem.builder()
                .job(job)
                .targetType("LAND")
                .payload("{}")
                .build());

        itemRepository.deleteByJob(job);
        jobRepository.delete(job);

        assertThat(itemRepository.findByJobOrderById(job)).isEmpty();
        assertThat(jobRepository.findById(job.getId())).isEmpty();
    }

    @Test
    void 만료된_익명_job만_조회된다() {
        User owner = userRepository.save(User.builder()
                .loginId("owner02")
                .email("owner02@example.com")
                .password("hashed")
                .name("소유자2")
                .provider(Provider.LOCAL)
                .role(Role.USER)
                .build());

        RecommendationJob oldAnonymous = jobRepository.save(RecommendationJob.builder()
                .externalJobId("job-old-anon")
                .originalFilename("오래된익명.xlsx")
                .limitParam(3)
                .status(JobStatus.QUEUED)
                .build());

        RecommendationJob recentAnonymous = jobRepository.save(RecommendationJob.builder()
                .externalJobId("job-recent-anon")
                .originalFilename("최근익명.xlsx")
                .limitParam(3)
                .status(JobStatus.QUEUED)
                .build());

        RecommendationJob oldOwned = jobRepository.save(RecommendationJob.builder()
                .externalJobId("job-old-owned")
                .user(owner)
                .originalFilename("오래된내job.xlsx")
                .limitParam(3)
                .status(JobStatus.QUEUED)
                .build());

        backdateCreatedAt(oldAnonymous.getId(), LocalDateTime.now().minusHours(25));
        backdateCreatedAt(oldOwned.getId(), LocalDateTime.now().minusHours(25));

        List<RecommendationJob> expired = jobRepository.findByUserIsNullAndCreatedAtBefore(
                LocalDateTime.now().minusHours(24)
        );

        assertThat(expired).extracting(RecommendationJob::getId).containsExactly(oldAnonymous.getId());
        assertThat(recentAnonymous.getId()).isNotIn(expired.stream().map(RecommendationJob::getId).toList());
    }

    @Test
    void deleteByJobIn으로_여러_job의_item을_한번에_지울_수_있다() {
        RecommendationJob job1 = jobRepository.save(RecommendationJob.builder()
                .externalJobId("job-bulk-1")
                .originalFilename("a.xlsx")
                .limitParam(3)
                .status(JobStatus.QUEUED)
                .build());

        RecommendationJob job2 = jobRepository.save(RecommendationJob.builder()
                .externalJobId("job-bulk-2")
                .originalFilename("b.xlsx")
                .limitParam(3)
                .status(JobStatus.QUEUED)
                .build());

        itemRepository.save(RecommendationItem.builder().job(job1).targetType("LAND").payload("{}").build());
        itemRepository.save(RecommendationItem.builder().job(job2).targetType("LAND").payload("{}").build());

        itemRepository.deleteByJobIn(List.of(job1, job2));
        jobRepository.deleteAll(List.of(job1, job2));

        assertThat(itemRepository.findByJobOrderById(job1)).isEmpty();
        assertThat(itemRepository.findByJobOrderById(job2)).isEmpty();
        assertThat(jobRepository.findById(job1.getId())).isEmpty();
        assertThat(jobRepository.findById(job2.getId())).isEmpty();
    }

    private void backdateCreatedAt(Long jobId, LocalDateTime createdAt) {
        entityManager.createNativeQuery("UPDATE recommendation_job SET created_at = ? WHERE id = ?")
                .setParameter(1, createdAt)
                .setParameter(2, jobId)
                .executeUpdate();
        entityManager.clear();
    }
}
