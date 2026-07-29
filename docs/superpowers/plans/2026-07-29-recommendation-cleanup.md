# 익명 추천 job 자동 정리 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 생성된 지 24시간 지난 익명(로그인 안 한 상태로 올린) 추천 job과 그 하위 항목을 1시간마다 자동 삭제하는 스케줄러를 추가한다.

**Architecture:** `RecommendationJobRepository`/`RecommendationItemRepository`에 정리용 조회/삭제 메서드를 추가하고, 신규 `RecommendationCleanupScheduler`(`@Scheduled`)가 이를 호출한다. 이 프로젝트 최초의 스케줄러라 `DemoApplication`에 `@EnableScheduling`을 추가해야 한다. 상세 설계: `docs/superpowers/specs/2026-07-29-recommendation-cleanup-design.md`.

**Tech Stack:** Spring Boot 4.1.0 / Spring Framework 7 / Jackson 3(기존 유지), Spring Data JPA, `@Scheduled`, JUnit 5 + Mockito, `@DataJpaTest`(H2).

## Global Constraints

- **이 프로젝트는 Spring Boot 4.1.0 / Spring Framework 7 / Jackson 3 기준이다.** `@DataJpaTest`는 `org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest`.
- **`RecommendationJob`의 `createdAt`은 `@CreatedDate` + `@Column(updatable = false)`다.** 테스트에서 "오래된 job"을 만들려면 `RecommendationJob.builder()`로 지정하거나(불가능 — 빌더에 그 필드가 없음), 저장 후 필드를 리플렉션으로 바꾸는 것도 안 통한다(auditing 리스너가 INSERT 시점에 값을 덮어쓰고, `updatable=false`라 이후 UPDATE로도 못 바꿈). **반드시 `EntityManager`로 네이티브 쿼리를 날려 DB 컬럼을 직접 백데이트하고, `entityManager.clear()`로 영속성 컨텍스트를 비워야** 이후 조회가 최신 DB 값을 읽는다. 아래 Task에 정확한 코드가 있다.
- 로그인 사용자의 job은 기간·상태와 무관하게 정리 대상에서 절대 제외한다.
- QUEUED/RUNNING 상태의 익명 job도 24시간 지났으면 그대로 삭제한다 (상태 구분 없음).
- `report`/`dashboard`/`SecurityConfig`는 손대지 않는다.

---

## Task 1: 익명 job 자동 정리 스케줄러

**Files:**
- Modify: `src/main/java/com/example/demo/DemoApplication.java`
- Modify: `src/main/java/com/example/demo/recommend/repository/RecommendationJobRepository.java`
- Modify: `src/main/java/com/example/demo/recommend/repository/RecommendationItemRepository.java`
- Create: `src/main/java/com/example/demo/recommend/service/RecommendationCleanupScheduler.java`
- Test: `src/test/java/com/example/demo/recommend/service/RecommendationCleanupSchedulerTest.java`
- Test: `src/test/java/com/example/demo/recommend/repository/RecommendationRepositoryTest.java`

**Interfaces:**
- Produces: `RecommendationJobRepository.findByUserIsNullAndCreatedAtBefore(LocalDateTime): List<RecommendationJob>`, `RecommendationItemRepository.deleteByJobIn(List<RecommendationJob>): void`, `RecommendationCleanupScheduler.cleanupExpiredAnonymousJobs(): void` (스케줄러가 호출하지만 테스트에서는 직접 호출).

- [ ] **Step 1: 리포지토리 테스트 추가 (실패하는 테스트)**

`RecommendationRepositoryTest.java`의 마지막 테스트(`deleteByJob으로_item을_지우면_그_다음_job도_삭제할_수_있다`) 다음, 클래스 닫는 중괄호 앞에 추가:

```java
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
```

파일 상단 import에 추가:

```java
import jakarta.persistence.EntityManager;

import java.time.LocalDateTime;
```

클래스 필드에 추가 (기존 `userRepository` 필드 다음):

```java
    @Autowired
    private EntityManager entityManager;
```

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests "com.example.demo.recommend.repository.RecommendationRepositoryTest"`
Expected: FAIL (컴파일 에러 — `findByUserIsNullAndCreatedAtBefore`, `deleteByJobIn` 메서드가 아직 없음)

- [ ] **Step 3: 리포지토리에 메서드 추가**

`RecommendationJobRepository.java` 전체를 아래로 교체:

```java
package com.example.demo.recommend.repository;

import com.example.demo.recommend.entity.RecommendationJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface RecommendationJobRepository extends JpaRepository<RecommendationJob, Long> {

    List<RecommendationJob> findTop10ByUser_IdOrderByCreatedAtDesc(Long userId);

    List<RecommendationJob> findByUserIsNullAndCreatedAtBefore(LocalDateTime cutoff);
}
```

`RecommendationItemRepository.java` 전체를 아래로 교체:

```java
package com.example.demo.recommend.repository;

import com.example.demo.recommend.entity.RecommendationItem;
import com.example.demo.recommend.entity.RecommendationJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RecommendationItemRepository extends JpaRepository<RecommendationItem, Long> {
    List<RecommendationItem> findByJobOrderById(RecommendationJob job);

    void deleteByJob(RecommendationJob job);

    void deleteByJobIn(List<RecommendationJob> jobs);
}
```

(`jobRepository.deleteAll(List)`은 `JpaRepository`가 이미 기본 제공하므로 별도로 선언할 필요 없다.)

- [ ] **Step 4: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests "com.example.demo.recommend.repository.RecommendationRepositoryTest"`
Expected: `BUILD SUCCESSFUL`, 6 tests passed

- [ ] **Step 5: 스케줄러 테스트 작성 (실패하는 테스트)**

`src/test/java/com/example/demo/recommend/service/RecommendationCleanupSchedulerTest.java`:

```java
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
```

- [ ] **Step 6: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests "com.example.demo.recommend.service.RecommendationCleanupSchedulerTest"`
Expected: FAIL (컴파일 에러 — `RecommendationCleanupScheduler`가 아직 없음)

- [ ] **Step 7: 스케줄러 구현**

`src/main/java/com/example/demo/recommend/service/RecommendationCleanupScheduler.java`:

```java
package com.example.demo.recommend.service;

import com.example.demo.recommend.entity.RecommendationJob;
import com.example.demo.recommend.repository.RecommendationItemRepository;
import com.example.demo.recommend.repository.RecommendationJobRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Component
public class RecommendationCleanupScheduler {

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
    }
}
```

- [ ] **Step 8: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests "com.example.demo.recommend.service.RecommendationCleanupSchedulerTest"`
Expected: `BUILD SUCCESSFUL`, 2 tests passed

- [ ] **Step 9: 스케줄링 활성화**

`DemoApplication.java`의 `import` 구문에 추가:

```java
import org.springframework.scheduling.annotation.EnableScheduling;
```

`@SpringBootApplication` 바로 아래 줄에 추가:

```java
@SpringBootApplication
@EnableScheduling
public class DemoApplication {
```

- [ ] **Step 10: 전체 테스트 스위트 실행**

Run: `./gradlew test`
Expected: 전체 통과. `JwtProviderTest`의 `위조된_토큰은_검증에_실패한다()`가 가끔 한 번씩 튀는 기존 플레이키 테스트로 알려져 있음 — 이거 하나만 실패하면 재실행해서 통과하는지 확인 (우리 변경과 무관).

- [ ] **Step 11: Commit**

```bash
git add src/main/java/com/example/demo/DemoApplication.java src/main/java/com/example/demo/recommend/repository/RecommendationJobRepository.java src/main/java/com/example/demo/recommend/repository/RecommendationItemRepository.java src/main/java/com/example/demo/recommend/service/RecommendationCleanupScheduler.java src/test/java/com/example/demo/recommend/service/RecommendationCleanupSchedulerTest.java src/test/java/com/example/demo/recommend/repository/RecommendationRepositoryTest.java
git commit -m "feat: 24시간 지난 익명 추천 job 자동 정리 스케줄러 추가"
```
