package com.example.demo.recommend.repository;

import com.example.demo.global.config.JpaAuditingConfig;
import com.example.demo.recommend.entity.JobStatus;
import com.example.demo.recommend.entity.RecommendationItem;
import com.example.demo.recommend.entity.RecommendationJob;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

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

        assertThat(itemRepository.findByJob(job)).containsExactly(reloadedItem);
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
}
