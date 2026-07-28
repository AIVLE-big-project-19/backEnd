# 추천 이력 목록 조회 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 로그인한 사용자가 자신이 올린 추천 job 최근 10건을 요약 정보로 조회하는 `GET /recommendations/me`를 추가한다.

**Architecture:** `RecommendationJobRepository`에 사용자별 최근 10건 조회 메서드를 추가하고, `RecommendService`가 이를 요약 DTO(`RecommendationHistoryResponse`)로 매핑해 반환한다. `RecommendationController`는 기존 `currentUserId()`를 재사용해 비로그인이면 서비스 호출 없이 즉시 실패 응답을 준다. 상세 설계: `docs/superpowers/specs/2026-07-28-recommendation-history-design.md`.

**Tech Stack:** Spring Boot 4.1.0 / Spring Framework 7 / Jackson 3(기존 유지), Spring Data JPA, JUnit 5 + Mockito, `@WebMvcTest`, `@DataJpaTest`(H2).

## Global Constraints

- **이 프로젝트는 Spring Boot 4.1.0 / Spring Framework 7 / Jackson 3 기준이다.** `@WebMvcTest`/`@AutoConfigureMockMvc`는 `org.springframework.boot.webmvc.test.autoconfigure.*`, `@DataJpaTest`는 `org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest`, `ObjectMapper`는 `tools.jackson.databind.ObjectMapper`.
- 이번 작업은 `recommend` 모듈의 기존 4개 파일(`RecommendationJobRepository`, `RecommendService`, `RecommendationController`, `SuccessCode`)에 추가만 한다 — 기존 `submit`/`getStatus`/`POST`/`GET /{id}` 동작은 변경하지 않는다.
- 응답은 요약 정보만 (`id`, `originalFilename`, `status`, `stage`, `errorMessage`, `createdAt`) — `funnel`/`recommendations` 상세는 포함하지 않는다 (기존 `GET /{id}`로 유도).
- 반환 건수는 최근 10건 고정, 페이지네이션 없음.
- 비로그인 시 서비스 호출 없이 컨트롤러에서 즉시 `ApiResponse.fail("로그인 후 추천 이력을 조회할 수 있습니다.")` 반환 (`DashboardController.history()`와 동일 패턴).
- `report`/`dashboard`/`SecurityConfig`는 손대지 않는다 — `GET /recommendations/me`는 기존 `anyRequest().permitAll()`에 자동으로 포함된다.

---

## Task 1: 추천 이력 목록 조회 (`GET /recommendations/me`)

**Files:**
- Modify: `src/main/java/com/example/demo/recommend/repository/RecommendationJobRepository.java`
- Create: `src/main/java/com/example/demo/recommend/dto/RecommendationHistoryResponse.java`
- Modify: `src/main/java/com/example/demo/recommend/service/RecommendService.java`
- Modify: `src/main/java/com/example/demo/recommend/controller/RecommendationController.java`
- Modify: `src/main/java/com/example/demo/global/response/SuccessCode.java`
- Test: `src/test/java/com/example/demo/recommend/repository/RecommendationRepositoryTest.java`
- Test: `src/test/java/com/example/demo/recommend/service/RecommendServiceTest.java`
- Test: `src/test/java/com/example/demo/recommend/controller/RecommendationControllerTest.java`

**Interfaces:**
- Produces: `RecommendationJobRepository.findTop10ByUser_IdOrderByCreatedAtDesc(Long): List<RecommendationJob>`, `RecommendationHistoryResponse(Long id, String originalFilename, String status, String stage, String errorMessage, LocalDateTime createdAt)`, `RecommendService.getHistory(Long userId): List<RecommendationHistoryResponse>`, `GET /recommendations/me`.

- [ ] **Step 1: 리포지토리 테스트 추가 (실패하는 테스트)**

`src/test/java/com/example/demo/recommend/repository/RecommendationRepositoryTest.java`의 `user가_없어도_job을_저장할_수_있다()` 테스트 다음에 추가:

```java
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
```

이 테스트를 컴파일하려면 파일 상단 import에 아래를 추가해야 한다:

```java
import com.example.demo.user.entity.Provider;
import com.example.demo.user.entity.Role;
import com.example.demo.user.entity.User;
import com.example.demo.user.repository.UserRepository;

import java.util.List;
```

그리고 클래스 필드에 아래를 추가한다 (기존 `itemRepository` 필드 다음):

```java
    @Autowired
    private UserRepository userRepository;
```

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests "com.example.demo.recommend.repository.RecommendationRepositoryTest"`
Expected: FAIL (컴파일 에러 — `findTop10ByUser_IdOrderByCreatedAtDesc` 메서드가 아직 없음)

- [ ] **Step 3: 리포지토리에 메서드 추가**

`RecommendationJobRepository.java` 전체를 아래로 교체:

```java
package com.example.demo.recommend.repository;

import com.example.demo.recommend.entity.RecommendationJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RecommendationJobRepository extends JpaRepository<RecommendationJob, Long> {

    List<RecommendationJob> findTop10ByUser_IdOrderByCreatedAtDesc(Long userId);
}
```

- [ ] **Step 4: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests "com.example.demo.recommend.repository.RecommendationRepositoryTest"`
Expected: `BUILD SUCCESSFUL`, 3 tests passed

- [ ] **Step 5: 서비스 테스트 추가 (실패하는 테스트)**

`src/test/java/com/example/demo/recommend/service/RecommendServiceTest.java`의 `queuedJob()` 메서드 앞(마지막 `@Test` 다음)에 추가:

```java
    @Test
    void getHistory는_리포지토리_결과를_요약_DTO로_매핑한다() {
        RecommendationJob doneJob = RecommendationJob.builder()
                .id(2L)
                .externalJobId("job-2")
                .originalFilename("완료파일.xlsx")
                .limitParam(3)
                .status(JobStatus.DONE)
                .createdAt(java.time.LocalDateTime.of(2026, 7, 28, 14, 0))
                .build();

        RecommendationJob failedJob = RecommendationJob.builder()
                .id(1L)
                .externalJobId("job-1")
                .originalFilename("실패파일.xlsx")
                .limitParam(3)
                .status(JobStatus.FAILED)
                .errorMessage("AI 서버가 재시작되어 이전 작업 기록이 사라졌습니다. 파일을 다시 업로드해주세요.")
                .createdAt(java.time.LocalDateTime.of(2026, 7, 28, 13, 0))
                .build();

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
```

`RecommendationJob.builder()`에 `createdAt(...)`을 직접 지정할 수 있는 이유: `BaseEntity`의 `createdAt` 필드는 `@Getter`만 있고 롬복 `@Builder`가 상위 클래스 필드까지 포함해서 빌더를 생성하므로 테스트에서 값을 직접 넣을 수 있다 (실제 DB 저장 시에는 `@CreatedDate`가 덮어씀).

파일 상단 import에 추가:

```java
import com.example.demo.recommend.dto.RecommendationHistoryResponse;
```

- [ ] **Step 6: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests "com.example.demo.recommend.service.RecommendServiceTest"`
Expected: FAIL (컴파일 에러 — `RecommendationHistoryResponse`와 `RecommendService.getHistory`가 아직 없음)

- [ ] **Step 7: DTO 작성**

`src/main/java/com/example/demo/recommend/dto/RecommendationHistoryResponse.java`:

```java
package com.example.demo.recommend.dto;

import java.time.LocalDateTime;

public record RecommendationHistoryResponse(
        Long id,
        String originalFilename,
        String status,
        String stage,
        String errorMessage,
        LocalDateTime createdAt
) {
}
```

- [ ] **Step 8: RecommendService에 getHistory 추가**

`RecommendService.java` 상단 import에 추가:

```java
import com.example.demo.recommend.dto.RecommendationHistoryResponse;
import org.springframework.transaction.annotation.Transactional;
```

`submit` 메서드 앞에 새 메서드 추가:

```java
    @Transactional(readOnly = true)
    public List<RecommendationHistoryResponse> getHistory(Long userId) {
        return jobRepository.findTop10ByUser_IdOrderByCreatedAtDesc(userId).stream()
                .map(job -> new RecommendationHistoryResponse(
                        job.getId(),
                        job.getOriginalFilename(),
                        job.getStatus().name(),
                        job.getStage(),
                        job.getErrorMessage(),
                        job.getCreatedAt()
                ))
                .toList();
    }
```

(이 메서드는 AI 서버 호출이 없는 단순 DB 조회라 클래스 상단 주석에 설명된 `TransactionTemplate` 분리가 필요 없다 — 다른 메서드들과 다르게 평범한 `@Transactional(readOnly = true)`를 쓴다.)

- [ ] **Step 9: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests "com.example.demo.recommend.service.RecommendServiceTest"`
Expected: `BUILD SUCCESSFUL`, 11 tests passed

- [ ] **Step 10: 컨트롤러 테스트 추가 (실패하는 테스트)**

`src/test/java/com/example/demo/recommend/controller/RecommendationControllerTest.java`의 마지막 테스트 다음에 추가:

```java
    @Test
    void 비로그인_상태로_이력을_조회하면_서비스_호출_없이_실패_응답을_준다() throws Exception {
        mockMvc.perform(get("/recommendations/me"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("로그인 후 추천 이력을 조회할 수 있습니다."));

        verify(recommendService, never()).getHistory(any());
    }

    @Test
    void 로그인_상태로_이력을_조회하면_userId로_조회한다() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(5L, null, List.of())
        );

        when(recommendService.getHistory(5L)).thenReturn(List.of(
                new RecommendationHistoryResponse(17L, "대전광역시_유휴공간.xlsx", "DONE", null, null,
                        java.time.LocalDateTime.of(2026, 7, 28, 14, 16))
        ));

        mockMvc.perform(get("/recommendations/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(17))
                .andExpect(jsonPath("$.data[0].status").value("DONE"));

        verify(recommendService).getHistory(5L);
    }
```

파일 상단 import에 추가:

```java
import com.example.demo.recommend.dto.RecommendationHistoryResponse;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
```

(`any`/`never`는 이미 다른 이름으로 import돼 있지 않은지 확인 — 현재 파일은 `any`/`eq`/`isNull`/`verify`/`when`만 import돼 있으므로 `never`만 새로 추가하면 된다. `any`는 이미 있음.)

- [ ] **Step 11: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests "com.example.demo.recommend.controller.RecommendationControllerTest"`
Expected: FAIL (컴파일 에러 — `RecommendService.getHistory`를 모킹하려는데 컨트롤러에 `/me` 매핑이 없어 404, 혹은 `getHistory` 메서드 자체는 Step 8에서 이미 추가됐으므로 컴파일은 되지만 `GET /recommendations/me` 라우트가 없어 두 테스트 모두 404로 실패)

- [ ] **Step 12: 컨트롤러에 엔드포인트 추가**

`SuccessCode.java`의 `RECOMMENDATION_STATUS_FOUND(...)` 뒤 세미콜론을 콤마로 바꾸고 추가:

```java
    RECOMMENDATION_STATUS_FOUND("추천 작업 상태 조회 성공"),
    RECOMMENDATION_HISTORY_FOUND("추천 이력 조회 성공");
```

`RecommendationController.java`의 `getStatus` 메서드 다음에 추가:

```java
    @GetMapping("/me")
    public ApiResponse<List<RecommendationHistoryResponse>> history() {
        Long userId = currentUserId();
        if (userId == null) {
            return ApiResponse.fail("로그인 후 추천 이력을 조회할 수 있습니다.");
        }
        return ApiResponse.success(SuccessCode.RECOMMENDATION_HISTORY_FOUND, recommendService.getHistory(userId));
    }
```

파일 상단 import에 추가:

```java
import com.example.demo.recommend.dto.RecommendationHistoryResponse;

import java.util.List;
```

- [ ] **Step 13: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests "com.example.demo.recommend.controller.RecommendationControllerTest"`
Expected: `BUILD SUCCESSFUL`, 6 tests passed

- [ ] **Step 14: 전체 테스트 스위트 실행**

Run: `./gradlew test`
Expected: 전체 통과. `JwtProviderTest`의 `위조된_토큰은_검증에_실패한다()`가 가끔 한 번씩 튀는 기존 플레이키 테스트로 알려져 있음 — 이거 하나만 실패하면 재실행해서 통과하는지 확인 (우리 변경과 무관).

- [ ] **Step 15: Commit**

```bash
git add src/main/java/com/example/demo/recommend/repository/RecommendationJobRepository.java src/main/java/com/example/demo/recommend/dto/RecommendationHistoryResponse.java src/main/java/com/example/demo/recommend/service/RecommendService.java src/main/java/com/example/demo/recommend/controller/RecommendationController.java src/main/java/com/example/demo/global/response/SuccessCode.java src/test/java/com/example/demo/recommend/repository/RecommendationRepositoryTest.java src/test/java/com/example/demo/recommend/service/RecommendServiceTest.java src/test/java/com/example/demo/recommend/controller/RecommendationControllerTest.java
git commit -m "feat: 로그인 사용자의 추천 이력 목록 조회(GET /recommendations/me) 추가"
```
