# AI 추천 서버(job 패턴) 연동 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** React가 올린 지자체 유휴재산 엑셀을 AI 서버(FastAPI)의 비동기 job API(`POST /recommend/jobs`, `GET /recommend/jobs/{id}`)로 넘기고, 완료된 결과(funnel + recommendations)를 DB에 저장해 React에 돌려준다.

**Architecture:** 신규 `recommend` 모듈. `RecommendClient`(RestClient, 짧은 타임아웃)가 AI 서버의 job 등록/폴링만 담당하고, `RecommendService`가 상태 전이(등록→QUEUED, 폴링 결과에 따라 RUNNING/DONE/FAILED)와 영속화를 담당한다. `RecommendationController`는 얇게 두 엔드포인트만 노출한다. 완료된 추천 항목은 기존 `report.dto.AiAnalysisResponse`를 그대로 재사용해 JSON으로 저장한다. 상세 설계: `docs/superpowers/specs/2026-07-28-ai-recommend-job-integration-design.md`.

**Tech Stack:** Spring Boot 4.1.0 / Spring Framework 7 / Jackson 3 (기존 유지), Spring Data JPA, JUnit 5 + Mockito, `MockRestServiceServer`, `@WebMvcTest`, `@DataJpaTest`(H2).

## Global Constraints

- **이 프로젝트는 Spring Boot 4.1.0 / Spring Framework 7 / Jackson 3 기준이다.** `ObjectMapper`는 `tools.jackson.databind.ObjectMapper` (databind가 `tools.jackson`으로 이동). `@JsonProperty`는 그대로 `com.fasterxml.jackson.annotation.JsonProperty` (annotations 모듈은 이동 안 함 — 기존 `report.dto.*` 파일들이 이미 이렇게 씀). 순수 Mockito 단위 테스트에서 실제 `ObjectMapper` 인스턴스가 필요하면 `tools.jackson.databind.json.JsonMapper.builder().build()`로 만든다(Jackson 3는 `new ObjectMapper()` 대신 빌더 생성을 권장).
- `@WebMvcTest`/`@AutoConfigureMockMvc`는 `org.springframework.boot.webmvc.test.autoconfigure.*` 패키지. `@MockBean` 대신 `org.springframework.test.context.bean.override.mockito.MockitoBean`. `@DataJpaTest`는 `org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest`. Spring Boot 3.x 예제의 import 경로를 베끼지 말 것.
- `writeValueAsString`/`readValue`/`readTree` 등 Jackson 3 databind 메서드는 unchecked 예외(`JacksonException` 계열)만 던진다 — 굳이 `throws`나 checked-exception catch를 추가하지 않는다. (컴파일 시 실제로 checked exception을 요구하면 그때 `try/catch`를 추가 — Task별 "빌드 확인" 스텝에서 바로 드러난다.)
- `report`/`dashboard` 모듈은 수정하지 않는다. `report.dto.AiAnalysisResponse`, `SiteInfo`, `ScoresAndEvaluation` 클래스만 import해서 재사용한다.
- AI 서버가 보내는 job 상태 문자열은 소문자(`"queued"`, `"running"`, `"done"`, `"failed"`)다. 우리 쪽 `JobStatus` enum은 대문자(`QUEUED`, `RUNNING`, `DONE`, `FAILED`) — 이 둘은 별개이며 `RecommendService`에서 수동으로 매핑한다.
- JSON을 통째로 저장하는 컬럼(`funnelJson`, `payload`, `errorMessage`)은 `@Column(columnDefinition = "TEXT")`를 쓴다 (`Board.content`와 동일 패턴).
- `SecurityConfig`는 수정하지 않는다 — `/recommendations/**`에 대한 매처가 없으므로 기존 `anyRequest().permitAll()`에 자동으로 포함된다.
- `docs/API_REFERENCE.md`는 원래 로그인/회원가입 전용 문서지만, 설계 문서(`## 문서`)에서 이 파일에 엔드포인트를 추가하도록 정했으므로 Task 5에서 새 섹션으로 덧붙인다 (제목과 범위가 안 맞는 건 알지만 다른 모듈들도 별도 레퍼런스 문서가 없어 대안이 없음).

---

## Task 1: 엔티티 + 리포지토리 (`recommend.entity`, `recommend.repository`)

**Files:**
- Create: `src/main/java/com/example/demo/recommend/entity/JobStatus.java`
- Create: `src/main/java/com/example/demo/recommend/entity/RecommendationJob.java`
- Create: `src/main/java/com/example/demo/recommend/entity/RecommendationItem.java`
- Create: `src/main/java/com/example/demo/recommend/repository/RecommendationJobRepository.java`
- Create: `src/main/java/com/example/demo/recommend/repository/RecommendationItemRepository.java`
- Test: `src/test/java/com/example/demo/recommend/repository/RecommendationRepositoryTest.java`

**Interfaces:**
- Produces: `JobStatus{QUEUED,RUNNING,DONE,FAILED}`, `RecommendationJob{id,externalJobId,user,originalFilename,limitParam,status,stage,errorMessage,funnelJson,startedAt,finishedAt, markRunning(String), markDone(String), markFailed(String)}`, `RecommendationItem{id,job,targetType,siteId,address,grade,totalScore,priorityRank,status,payload}`, `RecommendationJobRepository extends JpaRepository<RecommendationJob,Long>`, `RecommendationItemRepository{findByJob(RecommendationJob): List<RecommendationItem>}`. Task 2~4가 그대로 사용한다.

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/example/demo/recommend/repository/RecommendationRepositoryTest.java`:

```java
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
```

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests "com.example.demo.recommend.repository.RecommendationRepositoryTest"`
Expected: FAIL (컴파일 에러 — `recommend` 패키지의 클래스들이 아직 없음)

- [ ] **Step 3: JobStatus 작성**

`src/main/java/com/example/demo/recommend/entity/JobStatus.java`:

```java
package com.example.demo.recommend.entity;

public enum JobStatus {
    QUEUED, RUNNING, DONE, FAILED
}
```

- [ ] **Step 4: RecommendationJob 작성**

`src/main/java/com/example/demo/recommend/entity/RecommendationJob.java`:

```java
package com.example.demo.recommend.entity;

import com.example.demo.global.entity.BaseEntity;
import com.example.demo.user.entity.User;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "recommendation_job")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RecommendationJob extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 100)
    private String externalJobId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(nullable = false, length = 255)
    private String originalFilename;

    @Column(nullable = false)
    private int limitParam;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private JobStatus status;

    @Column(length = 50)
    private String stage;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    @Column(columnDefinition = "TEXT")
    private String funnelJson;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    public void markRunning(String stage) {
        if (this.status == JobStatus.QUEUED) {
            this.startedAt = LocalDateTime.now();
        }
        this.status = JobStatus.RUNNING;
        this.stage = stage;
    }

    public void markDone(String funnelJson) {
        this.status = JobStatus.DONE;
        this.funnelJson = funnelJson;
        this.finishedAt = LocalDateTime.now();
    }

    public void markFailed(String errorMessage) {
        this.status = JobStatus.FAILED;
        this.errorMessage = errorMessage;
        this.finishedAt = LocalDateTime.now();
    }
}
```

- [ ] **Step 5: RecommendationItem 작성**

`src/main/java/com/example/demo/recommend/entity/RecommendationItem.java`:

```java
package com.example.demo.recommend.entity;

import com.example.demo.global.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "recommendation_item")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RecommendationItem extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_id", nullable = false)
    private RecommendationJob job;

    @Column(length = 20)
    private String targetType;

    @Column(length = 50)
    private String siteId;

    @Column(length = 255)
    private String address;

    @Column(length = 10)
    private String grade;

    private Integer totalScore;

    @Column(length = 20)
    private String priorityRank;

    @Column(length = 20)
    private String status;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;
}
```

- [ ] **Step 6: 리포지토리 작성**

`src/main/java/com/example/demo/recommend/repository/RecommendationJobRepository.java`:

```java
package com.example.demo.recommend.repository;

import com.example.demo.recommend.entity.RecommendationJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface RecommendationJobRepository extends JpaRepository<RecommendationJob, Long> {
}
```

`src/main/java/com/example/demo/recommend/repository/RecommendationItemRepository.java`:

```java
package com.example.demo.recommend.repository;

import com.example.demo.recommend.entity.RecommendationItem;
import com.example.demo.recommend.entity.RecommendationJob;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RecommendationItemRepository extends JpaRepository<RecommendationItem, Long> {
    List<RecommendationItem> findByJob(RecommendationJob job);
}
```

- [ ] **Step 7: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests "com.example.demo.recommend.repository.RecommendationRepositoryTest"`
Expected: `BUILD SUCCESSFUL`, 2 tests passed

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/example/demo/recommend/entity src/main/java/com/example/demo/recommend/repository src/test/java/com/example/demo/recommend/repository
git commit -m "feat: 추천 job/item 엔티티 및 리포지토리 추가"
```

---

## Task 2: RecommendClient (AI 서버 job API 클라이언트)

**Files:**
- Modify: `src/main/java/com/example/demo/global/exception/ErrorCode.java`
- Modify: `src/main/java/com/example/demo/global/config/RestClientConfig.java`
- Modify: `src/main/resources/application.yaml`
- Create: `src/main/java/com/example/demo/recommend/client/dto/JobSubmitResult.java`
- Create: `src/main/java/com/example/demo/recommend/client/dto/JobResult.java`
- Create: `src/main/java/com/example/demo/recommend/client/dto/JobStatusResult.java`
- Create: `src/main/java/com/example/demo/recommend/client/RecommendClient.java`
- Test: `src/test/java/com/example/demo/recommend/client/RecommendClientTest.java`

**Interfaces:**
- Consumes: 없음 (Task 1과 독립).
- Produces: `ErrorCode.AI_RECOMMEND_FAILED`, `RecommendClient{submitJob(MultipartFile,int): JobSubmitResult, pollJob(String): JobStatusResult(null=404)}`, `JobStatusResult{status,stage,result,error}`, `JobResult{funnel: Map<String,Object>, recommendations: List<AiAnalysisResponse>}`. Task 3이 그대로 사용한다.

- [ ] **Step 1: ErrorCode 추가**

`ErrorCode.java`의 `ACCOUNT_LOCKED(...)` 뒤 세미콜론을 콤마로 바꾸고 추가:

```java
    ACCOUNT_LOCKED(HttpStatus.LOCKED, "로그인 시도 횟수를 초과하여 계정이 일시적으로 잠겼습니다."),
    AI_RECOMMEND_FAILED(HttpStatus.BAD_GATEWAY, "AI 추천 서버 호출에 실패했습니다."),
    RECOMMENDATION_JOB_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 추천 작업을 찾을 수 없습니다.");
```

- [ ] **Step 2: application.yaml에 설정값 추가**

`ai: server:` 블록의 `analyze-path` 다음 줄에 추가:

```yaml
    recommend-jobs-path: ${AI_SERVER_RECOMMEND_JOBS_PATH:/recommend/jobs}
    request-timeout-ms: ${AI_SERVER_REQUEST_TIMEOUT_MS:30000}
```

- [ ] **Step 3: RestClientConfig에 타임아웃 전용 빈 추가**

`RestClientConfig.java` 전체를 아래로 교체:

```java
package com.example.demo.global.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class RestClientConfig {

    @Bean
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }

    @Bean
    public RestClient.Builder timeoutBoundRestClientBuilder(
            @Value("${ai.server.request-timeout-ms}") long requestTimeoutMs
    ) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(requestTimeoutMs))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(requestTimeoutMs));

        return RestClient.builder().requestFactory(requestFactory);
    }
}
```

(`restClientBuilder()`는 `GoogleOAuthClient`/`AiAnalysisClient`가 이미 쓰고 있으므로 그대로 둔다. `timeoutBoundRestClientBuilder()`가 새로 추가하는 빈이다.)

- [ ] **Step 4: 실패하는 테스트 작성**

`src/test/java/com/example/demo/recommend/client/RecommendClientTest.java`:

```java
package com.example.demo.recommend.client;

import com.example.demo.global.exception.CustomException;
import com.example.demo.global.exception.ErrorCode;
import com.example.demo.recommend.client.dto.JobStatusResult;
import com.example.demo.recommend.client.dto.JobSubmitResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.http.HttpMethod.GET;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;
import static org.springframework.http.HttpStatus.NOT_FOUND;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RecommendClientTest {

    private MockRestServiceServer mockServer;
    private RecommendClient recommendClient;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        recommendClient = new RecommendClient(builder, "http://ai-server.test", "/recommend/jobs", JsonMapper.builder().build());
    }

    @Test
    void job_등록에_성공하면_jobId를_반환한다() {
        mockServer.expect(requestTo("http://ai-server.test/recommend/jobs?limit=3"))
                .andExpect(method(POST))
                .andRespond(withSuccess("{\"job_id\":\"job-123\",\"status\":\"queued\"}", MediaType.APPLICATION_JSON));

        MockMultipartFile file = new MockMultipartFile("file", "sites.xlsx", "application/vnd.ms-excel", "dummy".getBytes());

        JobSubmitResult result = recommendClient.submitJob(file, 3);

        assertThat(result.getJobId()).isEqualTo("job-123");
        mockServer.verify();
    }

    @Test
    void job_등록이_400이면_detail_메시지를_담은_예외를_던진다() {
        mockServer.expect(requestTo("http://ai-server.test/recommend/jobs?limit=3"))
                .andRespond(withBadRequest()
                        .body("{\"detail\":\"엑셀 파일(.xlsx, .xls)만 업로드할 수 있습니다.\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        MockMultipartFile file = new MockMultipartFile("file", "sites.txt", "text/plain", "dummy".getBytes());

        assertThatThrownBy(() -> recommendClient.submitJob(file, 3))
                .isInstanceOf(CustomException.class)
                .hasMessage("엑셀 파일(.xlsx, .xls)만 업로드할 수 있습니다.")
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.AI_RECOMMEND_FAILED);
    }

    @Test
    void 폴링_응답이_done이면_결과를_파싱한다() {
        mockServer.expect(requestTo("http://ai-server.test/recommend/jobs/job-123"))
                .andExpect(method(GET))
                .andRespond(withSuccess(
                        "{\"status\":\"done\",\"stage\":null,\"result\":{\"funnel\":{\"node0_parsed\":10},\"recommendations\":[]}}",
                        MediaType.APPLICATION_JSON
                ));

        JobStatusResult result = recommendClient.pollJob("job-123");

        assertThat(result.getStatus()).isEqualTo("done");
        assertThat(result.getResult().getFunnel()).containsEntry("node0_parsed", 10);
    }

    @Test
    void 폴링_응답이_404면_null을_반환한다() {
        mockServer.expect(requestTo("http://ai-server.test/recommend/jobs/lost-job"))
                .andRespond(withStatus(NOT_FOUND));

        JobStatusResult result = recommendClient.pollJob("lost-job");

        assertThat(result).isNull();
    }

    @Test
    void 폴링_중_서버오류가_나면_예외를_던진다() {
        mockServer.expect(requestTo("http://ai-server.test/recommend/jobs/job-500"))
                .andRespond(withStatus(INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> recommendClient.pollJob("job-500"))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.AI_RECOMMEND_FAILED);
    }
}
```

- [ ] **Step 5: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests "com.example.demo.recommend.client.RecommendClientTest"`
Expected: FAIL (컴파일 에러 — `RecommendClient`와 DTO들이 아직 없음)

- [ ] **Step 6: DTO 작성**

`src/main/java/com/example/demo/recommend/client/dto/JobSubmitResult.java`:

```java
package com.example.demo.recommend.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class JobSubmitResult {

    @JsonProperty("job_id")
    private String jobId;
}
```

`src/main/java/com/example/demo/recommend/client/dto/JobResult.java`:

```java
package com.example.demo.recommend.client.dto;

import com.example.demo.report.dto.AiAnalysisResponse;
import lombok.Getter;
import lombok.Setter;

import java.util.List;
import java.util.Map;

@Getter
@Setter
public class JobResult {

    private Map<String, Object> funnel;

    private List<AiAnalysisResponse> recommendations;
}
```

`src/main/java/com/example/demo/recommend/client/dto/JobStatusResult.java`:

```java
package com.example.demo.recommend.client.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class JobStatusResult {

    private String status; // queued | running | done | failed

    private String stage;

    private JobResult result; // status == "done"일 때만 채워짐

    private String error; // status == "failed"일 때만 채워짐
}
```

- [ ] **Step 7: RecommendClient 구현**

`src/main/java/com/example/demo/recommend/client/RecommendClient.java`:

```java
package com.example.demo.recommend.client;

import com.example.demo.global.exception.CustomException;
import com.example.demo.global.exception.ErrorCode;
import com.example.demo.recommend.client.dto.JobStatusResult;
import com.example.demo.recommend.client.dto.JobSubmitResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;

@Component
public class RecommendClient {

    private static final Logger log = LoggerFactory.getLogger(RecommendClient.class);

    private final RestClient restClient;
    private final String jobsPath;
    private final ObjectMapper objectMapper;

    public RecommendClient(
            @Qualifier("timeoutBoundRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("${ai.server.base-url}") String baseUrl,
            @Value("${ai.server.recommend-jobs-path}") String jobsPath,
            ObjectMapper objectMapper
    ) {
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
        this.jobsPath = jobsPath;
        this.objectMapper = objectMapper;
    }

    public JobSubmitResult submitJob(MultipartFile file, int limit) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", file.getResource());

        try {
            return restClient.post()
                    .uri(uriBuilder -> uriBuilder.path(jobsPath).queryParam("limit", limit).build())
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .body(JobSubmitResult.class);
        } catch (RestClientResponseException e) {
            log.warn("AI 추천 job 등록 실패: status={}, body={}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new CustomException(ErrorCode.AI_RECOMMEND_FAILED, extractDetail(e).orElse(ErrorCode.AI_RECOMMEND_FAILED.getMessage()));
        } catch (RestClientException e) {
            log.warn("AI 추천 job 등록 요청 실패", e);
            throw new CustomException(ErrorCode.AI_RECOMMEND_FAILED);
        }
    }

    public JobStatusResult pollJob(String externalJobId) {
        try {
            return restClient.get()
                    .uri(jobsPath + "/{id}", externalJobId)
                    .retrieve()
                    .body(JobStatusResult.class);
        } catch (HttpClientErrorException.NotFound e) {
            return null;
        } catch (RestClientResponseException e) {
            log.warn("AI 추천 job 폴링 실패: status={}, body={}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new CustomException(ErrorCode.AI_RECOMMEND_FAILED, extractDetail(e).orElse(ErrorCode.AI_RECOMMEND_FAILED.getMessage()));
        } catch (RestClientException e) {
            log.warn("AI 추천 job 폴링 요청 실패", e);
            throw new CustomException(ErrorCode.AI_RECOMMEND_FAILED);
        }
    }

    private Optional<String> extractDetail(RestClientResponseException e) {
        try {
            JsonNode node = objectMapper.readTree(e.getResponseBodyAsString());
            JsonNode detail = node.get("detail");
            return (detail != null && !detail.isNull()) ? Optional.of(detail.asString()) : Optional.empty();
        } catch (RuntimeException parseError) {
            return Optional.empty();
        }
    }
}
```

- [ ] **Step 8: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests "com.example.demo.recommend.client.RecommendClientTest"`
Expected: `BUILD SUCCESSFUL`, 5 tests passed. `JsonNode.asString()`이 이 Jackson 3 버전에 없다고 컴파일 에러가 나면 `asText()`로 바꾼다 (Jackson 2 계열 이름, 3에서도 남아있을 수 있음 — 컴파일러가 알려주는 쪽을 따른다).

- [ ] **Step 9: Commit**

```bash
git add src/main/java/com/example/demo/global/exception/ErrorCode.java src/main/java/com/example/demo/global/config/RestClientConfig.java src/main/resources/application.yaml src/main/java/com/example/demo/recommend/client src/test/java/com/example/demo/recommend/client
git commit -m "feat: AI 추천 job 등록/폴링 클라이언트(RecommendClient) 추가"
```

---

## Task 3: RecommendService (상태 전이 + 영속화)

**Files:**
- Create: `src/main/java/com/example/demo/recommend/dto/RecommendationSubmitResponse.java`
- Create: `src/main/java/com/example/demo/recommend/dto/RecommendationStatusResponse.java`
- Create: `src/main/java/com/example/demo/recommend/service/RecommendService.java`
- Test: `src/test/java/com/example/demo/recommend/service/RecommendServiceTest.java`

**Interfaces:**
- Consumes: `RecommendClient.submitJob/pollJob`, DTO들(Task 2), `RecommendationJob`/`RecommendationItem`/리포지토리(Task 1), `UserRepository`(기존 `user.repository`).
- Produces: `RecommendService{submit(MultipartFile,int,Long): RecommendationSubmitResponse, getStatus(Long): RecommendationStatusResponse}`. Task 4가 그대로 호출한다.

- [ ] **Step 1: 응답 DTO 작성**

`src/main/java/com/example/demo/recommend/dto/RecommendationSubmitResponse.java`:

```java
package com.example.demo.recommend.dto;

public record RecommendationSubmitResponse(Long id, String status) {
}
```

`src/main/java/com/example/demo/recommend/dto/RecommendationStatusResponse.java`:

```java
package com.example.demo.recommend.dto;

import com.example.demo.report.dto.AiAnalysisResponse;

import java.util.List;
import java.util.Map;

public record RecommendationStatusResponse(
        Long id,
        String status,
        String stage,
        Map<String, Object> funnel,
        List<AiAnalysisResponse> recommendations,
        String errorMessage
) {
}
```

- [ ] **Step 2: 실패하는 테스트 작성**

`src/test/java/com/example/demo/recommend/service/RecommendServiceTest.java`:

```java
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
```

- [ ] **Step 3: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests "com.example.demo.recommend.service.RecommendServiceTest"`
Expected: FAIL (컴파일 에러 — `RecommendService`가 아직 없음)

- [ ] **Step 4: RecommendService 구현**

`src/main/java/com/example/demo/recommend/service/RecommendService.java`:

```java
package com.example.demo.recommend.service;

import com.example.demo.global.exception.CustomException;
import com.example.demo.global.exception.ErrorCode;
import com.example.demo.recommend.client.RecommendClient;
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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

@Service
public class RecommendService {

    private static final Logger log = LoggerFactory.getLogger(RecommendService.class);

    private final RecommendClient recommendClient;
    private final RecommendationJobRepository jobRepository;
    private final RecommendationItemRepository itemRepository;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    public RecommendService(
            RecommendClient recommendClient,
            RecommendationJobRepository jobRepository,
            RecommendationItemRepository itemRepository,
            UserRepository userRepository,
            ObjectMapper objectMapper
    ) {
        this.recommendClient = recommendClient;
        this.jobRepository = jobRepository;
        this.itemRepository = itemRepository;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public RecommendationSubmitResponse submit(MultipartFile file, int limit, Long userId) {
        JobSubmitResult submitResult = recommendClient.submitJob(file, limit);
        User user = userId == null ? null : userRepository.findById(userId).orElse(null);

        RecommendationJob job = jobRepository.save(RecommendationJob.builder()
                .externalJobId(submitResult.getJobId())
                .user(user)
                .originalFilename(file.getOriginalFilename())
                .limitParam(limit)
                .status(JobStatus.QUEUED)
                .build());

        return new RecommendationSubmitResponse(job.getId(), job.getStatus().name());
    }

    @Transactional
    public RecommendationStatusResponse getStatus(Long jobId) {
        RecommendationJob job = jobRepository.findById(jobId)
                .orElseThrow(() -> new CustomException(ErrorCode.RECOMMENDATION_JOB_NOT_FOUND));

        if (job.getStatus() == JobStatus.QUEUED || job.getStatus() == JobStatus.RUNNING) {
            refreshFromAiServer(job);
        }

        return toResponse(job);
    }

    private void refreshFromAiServer(RecommendationJob job) {
        JobStatusResult polled;
        try {
            polled = recommendClient.pollJob(job.getExternalJobId());
        } catch (CustomException e) {
            log.warn("AI 서버 폴링 중 일시적 오류, 마지막 상태 유지: jobId={}", job.getId(), e);
            return;
        }

        if (polled == null) {
            job.markFailed("AI 서버가 재시작되어 이전 작업 기록이 사라졌습니다. 파일을 다시 업로드해주세요.");
            jobRepository.save(job);
            return;
        }

        switch (polled.getStatus()) {
            case "done" -> {
                job.markDone(objectMapper.writeValueAsString(polled.getResult().getFunnel()));
                saveItems(job, polled.getResult().getRecommendations());
                jobRepository.save(job);
            }
            case "failed" -> {
                job.markFailed(polled.getError());
                jobRepository.save(job);
            }
            case "running" -> {
                job.markRunning(polled.getStage());
                jobRepository.save(job);
            }
            default -> { } // "queued": 상태 변화 없음
        }
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

    private RecommendationStatusResponse toResponse(RecommendationJob job) {
        Map<String, Object> funnel = null;
        List<AiAnalysisResponse> recommendations = null;

        if (job.getStatus() == JobStatus.DONE) {
            funnel = readFunnel(job.getFunnelJson());
            recommendations = itemRepository.findByJob(job).stream()
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
```

- [ ] **Step 5: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests "com.example.demo.recommend.service.RecommendServiceTest"`
Expected: `BUILD SUCCESSFUL`, 5 tests passed. `objectMapper.writeValueAsString`/`readValue`가 checked exception을 요구한다는 컴파일 에러가 나면 해당 호출을 `try { ... } catch (RuntimeException e) { throw new IllegalStateException(...) }`로 감싼다 (Global Constraints 참고).

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/example/demo/recommend/dto src/main/java/com/example/demo/recommend/service src/test/java/com/example/demo/recommend/service
git commit -m "feat: RecommendService로 추천 job 등록/상태조회 및 결과 영속화 구현"
```

---

## Task 4: RecommendationController

**Files:**
- Modify: `src/main/java/com/example/demo/global/response/SuccessCode.java`
- Create: `src/main/java/com/example/demo/recommend/controller/RecommendationController.java`
- Test: `src/test/java/com/example/demo/recommend/controller/RecommendationControllerTest.java`

**Interfaces:**
- Consumes: `RecommendService.submit/getStatus` (Task 3).
- Produces: `POST /recommendations`, `GET /recommendations/{id}` (컨텍스트 패스 `/api` 포함 시 `/api/recommendations`).

- [ ] **Step 1: SuccessCode 추가**

`SuccessCode.java`의 `CHAT_EXCEL_ANALYZED(...)` 뒤 세미콜론을 콤마로 바꾸고 추가:

```java
    CHAT_EXCEL_ANALYZED("엑셀 후보지 분석이 완료되었습니다."),

    // Recommend
    RECOMMENDATION_SUBMITTED("추천 작업이 등록되었습니다."),
    RECOMMENDATION_STATUS_FOUND("추천 작업 상태 조회 성공");
```

- [ ] **Step 2: 실패하는 테스트 작성**

`src/test/java/com/example/demo/recommend/controller/RecommendationControllerTest.java`:

```java
package com.example.demo.recommend.controller;

import com.example.demo.recommend.dto.RecommendationStatusResponse;
import com.example.demo.recommend.dto.RecommendationSubmitResponse;
import com.example.demo.recommend.service.RecommendService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RecommendationController.class)
@AutoConfigureMockMvc(addFilters = false)
class RecommendationControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RecommendService recommendService;

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 비로그인_상태로_업로드하면_userId_없이_등록한다() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "sites.xlsx", "application/vnd.ms-excel", "dummy".getBytes());
        when(recommendService.submit(any(), eq(3), isNull()))
                .thenReturn(new RecommendationSubmitResponse(17L, "QUEUED"));

        mockMvc.perform(multipart("/recommendations").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(17))
                .andExpect(jsonPath("$.data.status").value("QUEUED"));

        verify(recommendService).submit(any(), eq(3), isNull());
    }

    @Test
    void 로그인_상태면_userId를_같이_전달한다() throws Exception {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(5L, null, List.of())
        );

        MockMultipartFile file = new MockMultipartFile("file", "sites.xlsx", "application/vnd.ms-excel", "dummy".getBytes());
        when(recommendService.submit(any(), eq(10), eq(5L)))
                .thenReturn(new RecommendationSubmitResponse(18L, "QUEUED"));

        mockMvc.perform(multipart("/recommendations?limit=10").file(file))
                .andExpect(status().isOk());

        verify(recommendService).submit(any(), eq(10), eq(5L));
    }

    @Test
    void 완료된_job을_조회하면_추천목록을_반환한다() throws Exception {
        when(recommendService.getStatus(17L)).thenReturn(new RecommendationStatusResponse(
                17L, "DONE", null, Map.of("node0_parsed", 230), List.of(), null
        ));

        mockMvc.perform(get("/recommendations/17"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DONE"))
                .andExpect(jsonPath("$.data.funnel.node0_parsed").value(230));
    }
}
```

- [ ] **Step 3: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests "com.example.demo.recommend.controller.RecommendationControllerTest"`
Expected: FAIL (컴파일 에러 — `RecommendationController`가 아직 없음)

- [ ] **Step 4: RecommendationController 구현**

`src/main/java/com/example/demo/recommend/controller/RecommendationController.java`:

```java
package com.example.demo.recommend.controller;

import com.example.demo.global.response.ApiResponse;
import com.example.demo.global.response.SuccessCode;
import com.example.demo.recommend.dto.RecommendationStatusResponse;
import com.example.demo.recommend.dto.RecommendationSubmitResponse;
import com.example.demo.recommend.service.RecommendService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/recommendations")
@RequiredArgsConstructor
public class RecommendationController {

    private final RecommendService recommendService;

    @PostMapping
    public ApiResponse<RecommendationSubmitResponse> submit(
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "3") int limit
    ) {
        RecommendationSubmitResponse response = recommendService.submit(file, limit, currentUserId());
        return ApiResponse.success(SuccessCode.RECOMMENDATION_SUBMITTED, response);
    }

    @GetMapping("/{id}")
    public ApiResponse<RecommendationStatusResponse> getStatus(@PathVariable Long id) {
        return ApiResponse.success(SuccessCode.RECOMMENDATION_STATUS_FOUND, recommendService.getStatus(id));
    }

    private Long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        Object principal = authentication == null ? null : authentication.getPrincipal();
        return principal instanceof Long id ? id : null;
    }
}
```

- [ ] **Step 5: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests "com.example.demo.recommend.controller.RecommendationControllerTest"`
Expected: `BUILD SUCCESSFUL`, 3 tests passed

- [ ] **Step 6: 전체 테스트 스위트 실행**

Run: `./gradlew test`
Expected: 전체 통과. 유일하게 허용되는 실패는 사전에 존재하던 `DemoApplicationTests.contextLoads()`(환경변수 `VWORLD_API_KEY` 미설정, 이 작업과 무관).

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/example/demo/global/response/SuccessCode.java src/main/java/com/example/demo/recommend/controller src/test/java/com/example/demo/recommend/controller
git commit -m "feat: 추천 업로드/상태조회 엔드포인트(RecommendationController) 추가"
```

---

## Task 5: API 문서화

**Files:**
- Modify: `docs/API_REFERENCE.md`

**Interfaces:**
- Consumes: 없음 (문서 전용, Task 4 완료 후 그 결과를 기술).

- [ ] **Step 1: 문서 끝에 새 섹션 추가**

`docs/API_REFERENCE.md` 파일 끝에 추가:

```markdown

## AI 추천 (job 패턴)

지자체 유휴재산 엑셀을 업로드하면 AI 서버가 비동기로 파이프라인을 돌려 태양광 후보지 추천 목록을 생성한다. 등록은 즉시 끝나고, 완료 여부는 폴링으로 확인한다.

### POST /recommendations
`multipart/form-data`: `file`(필수, 지자체 유휴재산 엑셀 .xlsx/.xls), query `limit`(선택, 기본 3)

로그인 불필요. 로그인 상태면 결과가 계정에 연결되어 저장된다.

응답:
```json
{ "success": true, "message": "추천 작업이 등록되었습니다.", "data": { "id": 17, "status": "QUEUED" } }
```

### GET /recommendations/{id}
등록 시 받은 `id`로 상태를 조회한다. 10~30초 주기로 폴링 권장.

```json
// 진행 중
{ "data": { "id": 17, "status": "RUNNING", "stage": "node3_features", "funnel": null, "recommendations": null, "errorMessage": null } }

// 완료
{ "data": { "id": 17, "status": "DONE", "stage": null, "funnel": { "node0_parsed": 230 }, "recommendations": [ /* ... */ ], "errorMessage": null } }

// 실패 (AI 서버 재시작으로 job 기록 소실 포함)
{ "data": { "id": 17, "status": "FAILED", "stage": null, "funnel": null, "recommendations": null, "errorMessage": "AI 서버가 재시작되어 이전 작업 기록이 사라졌습니다. 파일을 다시 업로드해주세요." } }
```

프론트는 HTTP 상태 코드로 분기하지 않고 항상 `status` 필드(`QUEUED`/`RUNNING`/`DONE`/`FAILED`)만 보면 된다 — job 소실도 200으로 내려간다.
```

- [ ] **Step 2: Commit**

```bash
git add docs/API_REFERENCE.md
git commit -m "docs: AI 추천(job 패턴) 엔드포인트 문서 추가"
```
