# 추천 이력 삭제 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `DELETE /recommendations/{id}`를 추가해 사용자가 자신의 추천 job 이력(및 그 하위 추천 항목들)을 삭제할 수 있게 한다.

**Architecture:** `RecommendationItemRepository`에 `deleteByJob` 삭제 메서드를 추가하고, `RecommendService`가 `getStatus`와 동일한 소유자 검증 로직으로 권한을 확인한 뒤 item → job 순서로 삭제한다. `RecommendationController`는 `currentUserId()`를 재사용해 요청자 id를 넘긴다. 상세 설계: `docs/superpowers/specs/2026-07-28-recommendation-delete-design.md`.

**Tech Stack:** Spring Boot 4.1.0 / Spring Framework 7 / Jackson 3(기존 유지), Spring Data JPA, JUnit 5 + Mockito, `@WebMvcTest`, `@DataJpaTest`(H2).

## Global Constraints

- **이 프로젝트는 Spring Boot 4.1.0 / Spring Framework 7 / Jackson 3 기준이다.** `@WebMvcTest`/`@AutoConfigureMockMvc`는 `org.springframework.boot.webmvc.test.autoconfigure.*`, `@DataJpaTest`는 `org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest`.
- **`RecommendationJob.builder()`에는 `createdAt(...)` 메서드가 없다** — `BaseEntity`(`@MappedSuperclass`)의 필드는 일반 롬복 `@Builder`(`@SuperBuilder` 아님)에 포함되지 않는다. 테스트에서 `createdAt`을 지정해야 하면 `org.springframework.test.util.ReflectionTestUtils.setField(job, "createdAt", ...)`를 쓴다 (이번 작업에서는 필요 없음, 참고용).
- `DELETE /recommendations/{id}`는 `GET /recommendations/{id}`와 완전히 동일한 권한 정책(permitAll + 소유자 검증)을 쓴다. `SecurityConfig`는 수정하지 않는다.
- 존재하지 않는 id, 소유자 불일치 모두 동일하게 `ErrorCode.RECOMMENDATION_JOB_NOT_FOUND`로 응답 (존재 자체를 노출하지 않는 기존 원칙).
- `report`/`dashboard`는 손대지 않는다.
- AI 서버에 취소 요청을 보내지 않는다 (그런 API가 없음) — 우리 DB 기록만 지운다.

---

## Task 1: 추천 이력 삭제 (`DELETE /recommendations/{id}`)

**Files:**
- Modify: `src/main/java/com/example/demo/recommend/repository/RecommendationItemRepository.java`
- Modify: `src/main/java/com/example/demo/recommend/service/RecommendService.java`
- Modify: `src/main/java/com/example/demo/recommend/controller/RecommendationController.java`
- Modify: `src/main/java/com/example/demo/global/response/SuccessCode.java`
- Test: `src/test/java/com/example/demo/recommend/repository/RecommendationRepositoryTest.java`
- Test: `src/test/java/com/example/demo/recommend/service/RecommendServiceTest.java`
- Test: `src/test/java/com/example/demo/recommend/controller/RecommendationControllerTest.java`

**Interfaces:**
- Produces: `RecommendationItemRepository.deleteByJob(RecommendationJob): void`, `RecommendService.delete(Long jobId, Long requesterUserId): void`, `DELETE /recommendations/{id}`.

- [ ] **Step 1: 리포지토리 테스트 추가 (실패하는 테스트)**

`RecommendationRepositoryTest.java`의 `사용자별_최근_10건만_생성일_내림차순으로_조회한다()` 테스트 다음(마지막 `@Test` 뒤, 클래스 닫는 중괄호 앞)에 추가:

```java
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
```

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests "com.example.demo.recommend.repository.RecommendationRepositoryTest"`
Expected: FAIL (컴파일 에러 — `deleteByJob` 메서드가 아직 없음)

- [ ] **Step 3: 리포지토리에 메서드 추가**

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
}
```

- [ ] **Step 4: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests "com.example.demo.recommend.repository.RecommendationRepositoryTest"`
Expected: `BUILD SUCCESSFUL`, 4 tests passed

- [ ] **Step 5: 서비스 테스트 추가 (실패하는 테스트)**

`RecommendServiceTest.java`의 `getHistory는_리포지토리_결과를_요약_DTO로_매핑한다()` 테스트 다음, `private RecommendationJob queuedJob()` 헬퍼 메서드 앞에 추가:

```java
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

```

(`queuedJob()`은 `user`를 설정하지 않으므로 익명 job이다 — 기존 헬퍼 그대로 재사용.)

- [ ] **Step 6: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests "com.example.demo.recommend.service.RecommendServiceTest"`
Expected: FAIL (컴파일 에러 — `RecommendService.delete`가 아직 없음)

- [ ] **Step 7: RecommendService에 delete 추가**

`RecommendService.java`의 `getHistory` 메서드 다음에 추가:

```java
    @Transactional
    public void delete(Long jobId, Long requesterUserId) {
        RecommendationJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new CustomException(ErrorCode.RECOMMENDATION_JOB_NOT_FOUND));

        if (job.getUser() != null && !job.getUser().getId().equals(requesterUserId)) {
            throw new CustomException(ErrorCode.RECOMMENDATION_JOB_NOT_FOUND);
        }

        itemRepository.deleteByJob(job);
        jobRepository.delete(job);
    }
```

(`getHistory`와 동일한 이유로 평범한 `@Transactional`을 쓴다 — AI 서버 호출이 없는 단순 DB 작업이라 `TransactionTemplate` 분리가 필요 없다. `import org.springframework.transaction.annotation.Transactional;`는 이미 파일에 있음.)

- [ ] **Step 8: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests "com.example.demo.recommend.service.RecommendServiceTest"`
Expected: `BUILD SUCCESSFUL`, 15 tests passed

- [ ] **Step 9: 컨트롤러 테스트 추가 (실패하는 테스트)**

`RecommendationControllerTest.java`의 마지막 테스트 다음에 추가:

```java
    @Test
    void 삭제_호출시_id와_userId를_서비스에_전달한다() throws Exception {
        mockMvc.perform(delete("/recommendations/17"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(recommendService).delete(17L, null);
    }

    @Test
    void 로그인_상태로_삭제하면_userId를_같이_전달한다() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(5L, null, List.of())
        );

        mockMvc.perform(delete("/recommendations/17"))
                .andExpect(status().isOk());

        verify(recommendService).delete(17L, 5L);
    }
```

파일 상단 import에 추가:

```java
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
```

- [ ] **Step 10: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests "com.example.demo.recommend.controller.RecommendationControllerTest"`
Expected: FAIL (404 — `DELETE /recommendations/{id}` 매핑이 아직 없음)

- [ ] **Step 11: 컨트롤러에 엔드포인트 추가**

`SuccessCode.java`의 `RECOMMENDATION_HISTORY_FOUND(...)` 뒤 세미콜론을 콤마로 바꾸고 추가:

```java
    RECOMMENDATION_HISTORY_FOUND("추천 이력 조회 성공"),
    RECOMMENDATION_DELETED("추천 이력이 삭제되었습니다.");
```

`RecommendationController.java`의 `history()` 메서드 다음에 추가:

```java
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        recommendService.delete(id, currentUserId());
        return ApiResponse.success(SuccessCode.RECOMMENDATION_DELETED);
    }
```

- [ ] **Step 12: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests "com.example.demo.recommend.controller.RecommendationControllerTest"`
Expected: `BUILD SUCCESSFUL`, 8 tests passed

- [ ] **Step 13: 전체 테스트 스위트 실행**

Run: `./gradlew test`
Expected: 전체 통과. `JwtProviderTest`의 `위조된_토큰은_검증에_실패한다()`가 가끔 한 번씩 튀는 기존 플레이키 테스트로 알려져 있음 — 이거 하나만 실패하면 재실행해서 통과하는지 확인 (우리 변경과 무관).

- [ ] **Step 14: Commit**

```bash
git add src/main/java/com/example/demo/recommend/repository/RecommendationItemRepository.java src/main/java/com/example/demo/recommend/service/RecommendService.java src/main/java/com/example/demo/recommend/controller/RecommendationController.java src/main/java/com/example/demo/global/response/SuccessCode.java src/test/java/com/example/demo/recommend/repository/RecommendationRepositoryTest.java src/test/java/com/example/demo/recommend/service/RecommendServiceTest.java src/test/java/com/example/demo/recommend/controller/RecommendationControllerTest.java
git commit -m "feat: 추천 이력 삭제(DELETE /recommendations/{id}) 추가"
```
