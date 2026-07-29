# /recommendations 제거 + POST /vision-analysis/csv 신규 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 기존 `/recommendations` (엑셀 업로드 → 비동기 job → 폴링 → ML 채점) 기능을 완전히 삭제하고, 그 대신 채점 없이 Node 0~5까지만 실행한 CSV 결과를 그대로 다운로드하는 `POST /vision-analysis/csv`를 새로 추가한다.

**Architecture:** `recommend` 패키지(엔티티/리포지토리/서비스/컨트롤러/클라이언트/스케줄러) 전체와 그 설정을 삭제한 뒤, 같은 저장소에 새 `visionanalysis` 패키지(client/controller)를 만들어 AI 서버의 `POST /vision/csv`를 그대로 프록시한다. 저장/이력/인증 없음. `ReportController.getPdf`의 `ResponseEntity<byte[]>` 패턴과 `RecommendClient`의 에러 처리 패턴을 재사용한다.

**Tech Stack:** Spring Boot 4.1.0 / Spring Framework 7 / Jackson 3 (`tools.jackson.databind.*`), `RestClient`(JDK `HttpClient`), JUnit5 + AssertJ + Mockito, `MockRestServiceServer`, `@WebMvcTest`.

## Global Constraints

- Jackson 3 import: `tools.jackson.databind.ObjectMapper`, `tools.jackson.databind.JsonNode`, `tools.jackson.databind.json.JsonMapper` (NOT `com.fasterxml.jackson.databind.*`).
- `@WebMvcTest`/`@AutoConfigureMockMvc`는 `org.springframework.boot.webmvc.test.autoconfigure.*` 패키지에서 import.
- Mockito bean override는 `org.springframework.test.context.bean.override.mockito.MockitoBean` (`@MockBean` 아님).
- AI 서버(uvicorn/h11) 호출용 `RestClient`는 반드시 `HttpClient.Version.HTTP_1_1`을 강제해야 한다 — 강제하지 않으면 JDK `HttpClient`의 자동 h2c 업그레이드 시도를 AI 서버가 거부한다.
- 삭제 후에도 `./gradlew compileJava`와 `./gradlew test`가 전부 통과해야 한다(다른 모듈이 `recommend` 관련 클래스를 참조하지 않는지 최종 확인 포함).

---

### Task 1: `/recommendations` 전체 삭제

**Files:**
- Delete: `src/main/java/com/example/demo/recommend/` 디렉터리 전체 (client, client/dto, controller, dto, entity, repository, service 하위 15개 파일)
- Delete: `src/test/java/com/example/demo/recommend/` 디렉터리 전체 (client, controller, service, repository 하위 5개 테스트 파일)
- Modify: `src/main/java/com/example/demo/global/config/SecurityConfig.java`
- Modify: `src/main/java/com/example/demo/DemoApplication.java`
- Modify: `src/main/java/com/example/demo/global/exception/ErrorCode.java`
- Modify: `src/main/java/com/example/demo/global/response/SuccessCode.java`
- Modify: `src/main/java/com/example/demo/global/config/RestClientConfig.java`
- Modify: `src/main/resources/application.yaml`
- Modify: `docs/API_REFERENCE.md`

**Interfaces:**
- Consumes: 없음 (순수 삭제 작업)
- Produces: `ErrorCode`, `SuccessCode`, `RestClientConfig`, `application.yaml`의 "정리된" 상태 — Task 2가 그 위에 새 항목을 추가함

- [ ] **Step 1: `recommend` 패키지(main) 전체 삭제**

```bash
git rm -r src/main/java/com/example/demo/recommend
```

- [ ] **Step 2: `recommend` 패키지(test) 전체 삭제**

```bash
git rm -r src/test/java/com/example/demo/recommend
```

- [ ] **Step 3: `SecurityConfig`에서 `/recommendations/*` 인증 매처 제거**

`src/main/java/com/example/demo/global/config/SecurityConfig.java`에서 다음 줄을 삭제:

```java
                        .requestMatchers(HttpMethod.DELETE, "/recommendations/*").authenticated()
```

- [ ] **Step 4: `DemoApplication`에서 `@EnableScheduling` 제거**

`src/main/java/com/example/demo/DemoApplication.java`를 다음과 같이 수정(9번째 줄의 어노테이션과 6번째 줄의 import 삭제):

```java
package com.example.demo;

import io.github.cdimascio.dotenv.Dotenv;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class DemoApplication {

	public static void main(String[] args) {
		Dotenv dotenv = Dotenv.load();
		setIfPresent(dotenv, "VWORLD_API_KEY");
		setIfPresent(dotenv, "MAIL_USERNAME");
		setIfPresent(dotenv, "MAIL_PASSWORD");
		setIfPresent(dotenv, "JWT_SECRET");
		setIfPresent(dotenv, "OPENAI_API_KEY");
		setIfPresent(dotenv, "GOOGLE_CLIENT_ID");
		setIfPresent(dotenv, "GOOGLE_CLIENT_SECRET");

		SpringApplication.run(DemoApplication.class, args);
	}

	private static void setIfPresent(Dotenv dotenv, String key) {
		String value = dotenv.get(key);
		if (value != null) {
			System.setProperty(key, value);
		}
	}


}
```

- [ ] **Step 5: `ErrorCode`에서 추천 관련 항목 제거**

`src/main/java/com/example/demo/global/exception/ErrorCode.java`에서 다음 두 줄을 삭제:

```java
    AI_RECOMMEND_FAILED(HttpStatus.BAD_GATEWAY, "AI 추천 서버 호출에 실패했습니다."),
    RECOMMENDATION_JOB_NOT_FOUND(HttpStatus.NOT_FOUND, "요청한 추천 작업을 찾을 수 없습니다."),
```

(이 줄 삭제 후 `ACCOUNT_LOCKED` 항목의 콤마는 그대로 유지하고, 바로 다음 줄인 `IDLE_LAND_CSV_PARSE_FAILED` 항목과 이어지도록 한다.)

- [ ] **Step 6: `SuccessCode`에서 추천 관련 항목 제거**

`src/main/java/com/example/demo/global/response/SuccessCode.java`에서 다음 블록을 삭제:

```java
    // Recommend
    RECOMMENDATION_SUBMITTED("추천 작업이 등록되었습니다."),
    RECOMMENDATION_STATUS_FOUND("추천 작업 상태 조회 성공"),
    RECOMMENDATION_HISTORY_FOUND("추천 이력 조회 성공"),
    RECOMMENDATION_DELETED("추천 이력이 삭제되었습니다."),

```

- [ ] **Step 7: `RestClientConfig`에서 `timeoutBoundRestClientBuilder` 빈 제거**

`src/main/java/com/example/demo/global/config/RestClientConfig.java`를 다음으로 교체(Task 2에서 새 빈을 추가할 것이므로 지금은 기존 빈만 제거):

```java
package com.example.demo.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    @Bean
    public RestClient.Builder restClientBuilder() {
        return RestClient.builder();
    }
}
```

- [ ] **Step 8: `application.yaml`에서 추천 관련 키 제거**

`src/main/resources/application.yaml`의 `ai: server:` 블록을 다음으로 교체 (60~66번째 줄):

```yaml
ai:
  server:
    # AI 분석 서버 나중에 수정하기
    base-url: ${AI_SERVER_URL:http://localhost:8000}
    analyze-path: ${AI_SERVER_ANALYZE_PATH:/analyze}
```

- [ ] **Step 9: `docs/API_REFERENCE.md`에서 "AI 추천 (job 패턴)" 섹션 제거**

233~262번째 줄의 다음 전체 섹션을 삭제 (파일 끝까지):

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

(참고: `GET /recommendations/me`, `DELETE /recommendations/{id}` 관련 문서가 이 파일 다른 곳에 더 있다면 함께 삭제한다 — 삭제 전 `grep -n "recommendations" docs/API_REFERENCE.md`로 전체 위치를 먼저 확인할 것.)

- [ ] **Step 10: 컴파일 확인**

Run: `./gradlew compileJava compileTestJava`
Expected: BUILD SUCCESSFUL (recommend 관련 참조가 다른 파일에 남아있다면 여기서 컴파일 에러로 드러남)

- [ ] **Step 11: 전체 테스트 확인**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 12: Commit**

```bash
git add -A
git commit -m "refactor: /recommendations 기능 전체 제거"
```

---

### Task 2: `VisionAnalysisClient` (AI 서버 `/vision/csv` 호출 클라이언트)

**Files:**
- Modify: `src/main/java/com/example/demo/global/exception/ErrorCode.java`
- Modify: `src/main/java/com/example/demo/global/config/RestClientConfig.java`
- Modify: `src/main/resources/application.yaml`
- Create: `src/main/java/com/example/demo/visionanalysis/client/VisionAnalysisClient.java`
- Test: `src/test/java/com/example/demo/visionanalysis/client/VisionAnalysisClientTest.java`

**Interfaces:**
- Consumes: `CustomException(ErrorCode, String)` / `CustomException(ErrorCode)` (`com.example.demo.global.exception`), `RestClient.Builder` (Spring 표준)
- Produces: `VisionAnalysisClient.fetchCsv(MultipartFile file, int limit) : byte[]` — Task 3의 `VisionAnalysisController`가 이 메서드를 그대로 호출함. 실패 시 `CustomException(ErrorCode.VISION_ANALYSIS_FAILED, <detail 메시지>)`를 던진다.

- [ ] **Step 1: `ErrorCode`에 `VISION_ANALYSIS_FAILED` 추가**

`src/main/java/com/example/demo/global/exception/ErrorCode.java`의 `ACCOUNT_LOCKED` 항목 바로 다음(기존 `AI_RECOMMEND_FAILED`가 있던 자리)에 추가:

```java
    VISION_ANALYSIS_FAILED(HttpStatus.BAD_GATEWAY, "비전 분석 서버 호출에 실패했습니다."),
```

- [ ] **Step 2: `application.yaml`에 vision-csv 관련 키 추가**

`src/main/resources/application.yaml`의 `ai: server:` 블록을 다음으로 교체:

```yaml
ai:
  server:
    # AI 분석 서버 나중에 수정하기
    base-url: ${AI_SERVER_URL:http://localhost:8000}
    analyze-path: ${AI_SERVER_ANALYZE_PATH:/analyze}
    vision-csv-path: ${AI_SERVER_VISION_CSV_PATH:/vision/csv}
    vision-csv-timeout-ms: ${AI_SERVER_VISION_CSV_TIMEOUT_MS:900000}
```

- [ ] **Step 3: `RestClientConfig`에 15분 타임아웃 빈 추가**

`src/main/java/com/example/demo/global/config/RestClientConfig.java`를 다음으로 교체:

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
    public RestClient.Builder visionCsvRestClientBuilder(
            @Value("${ai.server.vision-csv-timeout-ms}") long timeoutMs
    ) {
        // HTTP_1_1을 명시하지 않으면 JDK HttpClient가 매 요청마다 cleartext HTTP/2(h2c) 업그레이드를
        // 먼저 시도한다. AI 서버(uvicorn/h11)는 이 업그레이드 요청을 거부하므로 반드시 HTTP/1.1을 강제해야 한다.
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(timeoutMs))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(timeoutMs));

        return RestClient.builder().requestFactory(requestFactory);
    }
}
```

- [ ] **Step 4: 실패 케이스 테스트 작성 (RED)**

Create `src/test/java/com/example/demo/visionanalysis/client/VisionAnalysisClientTest.java`:

```java
package com.example.demo.visionanalysis.client;

import com.example.demo.global.exception.CustomException;
import com.example.demo.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.http.HttpMethod.POST;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR;

class VisionAnalysisClientTest {

    private MockRestServiceServer mockServer;
    private VisionAnalysisClient visionAnalysisClient;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        visionAnalysisClient = new VisionAnalysisClient(builder, "http://ai-server.test", "/vision/csv", JsonMapper.builder().build());
    }

    @Test
    void 분석에_성공하면_CSV_바이트를_그대로_반환한다() {
        byte[] csvBytes = "id,score\n1,0.9\n".getBytes();
        mockServer.expect(requestTo("http://ai-server.test/vision/csv?limit=3"))
                .andExpect(method(POST))
                .andRespond(withSuccess(csvBytes, MediaType.parseMediaType("text/csv")));

        MockMultipartFile file = new MockMultipartFile("file", "sites.csv", "text/csv", "dummy".getBytes());

        byte[] result = visionAnalysisClient.fetchCsv(file, 3);

        assertThat(result).isEqualTo(csvBytes);
        mockServer.verify();
    }

    @Test
    void 파일_파트에_원본_파일명이_그대로_실린다() {
        mockServer.expect(requestTo("http://ai-server.test/vision/csv?limit=3"))
                .andExpect(method(POST))
                .andExpect(content().string(containsString("filename=\"sites.csv\"")))
                .andRespond(withSuccess("id\n1\n".getBytes(), MediaType.parseMediaType("text/csv")));

        MockMultipartFile file = new MockMultipartFile("file", "sites.csv", "text/csv", "dummy".getBytes());

        visionAnalysisClient.fetchCsv(file, 3);

        mockServer.verify();
    }

    @Test
    void 분석이_400이면_detail_메시지를_담은_예외를_던진다() {
        mockServer.expect(requestTo("http://ai-server.test/vision/csv?limit=3"))
                .andRespond(withBadRequest()
                        .body("{\"detail\":\"CSV 파일만 업로드할 수 있습니다.\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        MockMultipartFile file = new MockMultipartFile("file", "sites.txt", "text/plain", "dummy".getBytes());

        assertThatThrownBy(() -> visionAnalysisClient.fetchCsv(file, 3))
                .isInstanceOf(CustomException.class)
                .hasMessage("CSV 파일만 업로드할 수 있습니다.")
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.VISION_ANALYSIS_FAILED);
    }

    @Test
    void 분석이_400이고_body에_detail이_없으면_기본_메시지를_담은_예외를_던진다() {
        mockServer.expect(requestTo("http://ai-server.test/vision/csv?limit=3"))
                .andRespond(withBadRequest()
                        .body("{\"other_field\":\"value\"}")
                        .contentType(MediaType.APPLICATION_JSON));

        MockMultipartFile file = new MockMultipartFile("file", "sites.txt", "text/plain", "dummy".getBytes());

        assertThatThrownBy(() -> visionAnalysisClient.fetchCsv(file, 3))
                .isInstanceOf(CustomException.class)
                .hasMessage(ErrorCode.VISION_ANALYSIS_FAILED.getMessage())
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.VISION_ANALYSIS_FAILED);
    }

    @Test
    void 서버_오류가_나면_예외를_던진다() {
        mockServer.expect(requestTo("http://ai-server.test/vision/csv?limit=3"))
                .andRespond(withStatus(INTERNAL_SERVER_ERROR));

        MockMultipartFile file = new MockMultipartFile("file", "sites.csv", "text/csv", "dummy".getBytes());

        assertThatThrownBy(() -> visionAnalysisClient.fetchCsv(file, 3))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.VISION_ANALYSIS_FAILED);
    }
}
```

- [ ] **Step 5: 테스트 실행하여 실패 확인 (RED)**

Run: `./gradlew test --tests "com.example.demo.visionanalysis.client.VisionAnalysisClientTest"`
Expected: FAIL (컴파일 에러 — `VisionAnalysisClient` 클래스가 아직 없음)

- [ ] **Step 6: `VisionAnalysisClient` 구현**

Create `src/main/java/com/example/demo/visionanalysis/client/VisionAnalysisClient.java`:

```java
package com.example.demo.visionanalysis.client;

import com.example.demo.global.exception.CustomException;
import com.example.demo.global.exception.ErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.Optional;

@Component
public class VisionAnalysisClient {

    private static final Logger log = LoggerFactory.getLogger(VisionAnalysisClient.class);

    private final RestClient restClient;
    private final String visionCsvPath;
    private final ObjectMapper objectMapper;

    public VisionAnalysisClient(
            @Qualifier("visionCsvRestClientBuilder") RestClient.Builder restClientBuilder,
            @Value("${ai.server.base-url}") String baseUrl,
            @Value("${ai.server.vision-csv-path}") String visionCsvPath,
            ObjectMapper objectMapper
    ) {
        this.restClient = restClientBuilder.baseUrl(baseUrl).build();
        this.visionCsvPath = visionCsvPath;
        this.objectMapper = objectMapper;
    }

    public byte[] fetchCsv(MultipartFile file, int limit) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", file.getResource());

        try {
            return restClient.post()
                    .uri(uriBuilder -> uriBuilder.path(visionCsvPath).queryParam("limit", limit).build())
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .body(byte[].class);
        } catch (RestClientResponseException e) {
            log.warn("비전 분석 요청 실패: status={}, body={}", e.getStatusCode(), e.getResponseBodyAsString());
            throw new CustomException(ErrorCode.VISION_ANALYSIS_FAILED, extractDetail(e).orElse(ErrorCode.VISION_ANALYSIS_FAILED.getMessage()));
        } catch (RestClientException e) {
            log.warn("비전 분석 요청 실패", e);
            throw new CustomException(ErrorCode.VISION_ANALYSIS_FAILED);
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

- [ ] **Step 7: 테스트 실행하여 통과 확인 (GREEN)**

Run: `./gradlew test --tests "com.example.demo.visionanalysis.client.VisionAnalysisClientTest"`
Expected: BUILD SUCCESSFUL, 5 tests passed

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/example/demo/global/exception/ErrorCode.java \
        src/main/java/com/example/demo/global/config/RestClientConfig.java \
        src/main/resources/application.yaml \
        src/main/java/com/example/demo/visionanalysis/client/VisionAnalysisClient.java \
        src/test/java/com/example/demo/visionanalysis/client/VisionAnalysisClientTest.java
git commit -m "feat: VisionAnalysisClient로 AI 서버 /vision/csv 연동"
```

---

### Task 3: `VisionAnalysisController` (`POST /vision-analysis/csv`)

**Files:**
- Create: `src/main/java/com/example/demo/visionanalysis/controller/VisionAnalysisController.java`
- Test: `src/test/java/com/example/demo/visionanalysis/controller/VisionAnalysisControllerTest.java`

**Interfaces:**
- Consumes: `VisionAnalysisClient.fetchCsv(MultipartFile, int) : byte[]` (Task 2에서 정의, 실패 시 `CustomException(ErrorCode.VISION_ANALYSIS_FAILED, message)` 던짐), `GlobalExceptionHandler.handleCustomException`(기존 코드, 수정 없음 — `CustomException`을 표준 JSON 에러로 변환)
- Produces: `POST /vision-analysis/csv` 엔드포인트. 인증 불필요(`SecurityConfig`의 `anyRequest().permitAll()`에 이미 포함되어 있으므로 `SecurityConfig` 수정 불필요).

- [ ] **Step 1: 컨트롤러 테스트 작성 (RED)**

Create `src/test/java/com/example/demo/visionanalysis/controller/VisionAnalysisControllerTest.java`:

```java
package com.example.demo.visionanalysis.controller;

import com.example.demo.global.exception.CustomException;
import com.example.demo.global.exception.ErrorCode;
import com.example.demo.visionanalysis.client.VisionAnalysisClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(VisionAnalysisController.class)
@AutoConfigureMockMvc(addFilters = false)
class VisionAnalysisControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private VisionAnalysisClient visionAnalysisClient;

    @Test
    void 분석에_성공하면_CSV_바이트와_헤더를_그대로_반환한다() throws Exception {
        byte[] csvBytes = "id,score\n1,0.9\n".getBytes();
        MockMultipartFile file = new MockMultipartFile("file", "sites.csv", "text/csv", "dummy".getBytes());
        when(visionAnalysisClient.fetchCsv(any(), eq(3))).thenReturn(csvBytes);

        mockMvc.perform(multipart("/vision-analysis/csv").file(file))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/csv"))
                .andExpect(header().string("Content-Disposition", "form-data; name=\"attachment\"; filename=\"vision_analysis_result.csv\""))
                .andExpect(content().bytes(csvBytes));

        verify(visionAnalysisClient).fetchCsv(any(), eq(3));
    }

    @Test
    void limit_쿼리파라미터를_클라이언트에_그대로_전달한다() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "sites.csv", "text/csv", "dummy".getBytes());
        when(visionAnalysisClient.fetchCsv(any(), eq(10))).thenReturn("id\n1\n".getBytes());

        mockMvc.perform(multipart("/vision-analysis/csv?limit=10").file(file))
                .andExpect(status().isOk());

        verify(visionAnalysisClient).fetchCsv(any(), eq(10));
    }

    @Test
    void 클라이언트가_예외를_던지면_표준_에러_응답을_반환한다() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "sites.txt", "text/plain", "dummy".getBytes());
        when(visionAnalysisClient.fetchCsv(any(), eq(3)))
                .thenThrow(new CustomException(ErrorCode.VISION_ANALYSIS_FAILED, "CSV 파일만 업로드할 수 있습니다."));

        mockMvc.perform(multipart("/vision-analysis/csv").file(file))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("CSV 파일만 업로드할 수 있습니다."));
    }
}
```

- [ ] **Step 2: 테스트 실행하여 실패 확인 (RED)**

Run: `./gradlew test --tests "com.example.demo.visionanalysis.controller.VisionAnalysisControllerTest"`
Expected: FAIL (컴파일 에러 — `VisionAnalysisController` 클래스가 아직 없음)

- [ ] **Step 3: `VisionAnalysisController` 구현**

Create `src/main/java/com/example/demo/visionanalysis/controller/VisionAnalysisController.java`:

```java
package com.example.demo.visionanalysis.controller;

import com.example.demo.visionanalysis.client.VisionAnalysisClient;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/vision-analysis")
@RequiredArgsConstructor
public class VisionAnalysisController {

    private final VisionAnalysisClient visionAnalysisClient;

    @PostMapping("/csv")
    public ResponseEntity<byte[]> downloadCsv(
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "3") int limit
    ) {
        byte[] csv = visionAnalysisClient.fetchCsv(file, limit);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType("text/csv"));
        headers.setContentDispositionFormData("attachment", "vision_analysis_result.csv");
        return new ResponseEntity<>(csv, headers, HttpStatus.OK);
    }
}
```

- [ ] **Step 4: 테스트 실행하여 통과 확인 (GREEN)**

Run: `./gradlew test --tests "com.example.demo.visionanalysis.controller.VisionAnalysisControllerTest"`
Expected: BUILD SUCCESSFUL, 3 tests passed

- [ ] **Step 5: 전체 빌드 확인**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/example/demo/visionanalysis/controller/VisionAnalysisController.java \
        src/test/java/com/example/demo/visionanalysis/controller/VisionAnalysisControllerTest.java
git commit -m "feat: POST /vision-analysis/csv 엔드포인트 추가"
```

---

### Task 4: API 문서화

**Files:**
- Modify: `docs/API_REFERENCE.md`

**Interfaces:**
- Consumes: Task 3에서 확정된 `POST /vision-analysis/csv` 실제 동작(요청/응답 형식)
- Produces: 없음 (문서 전용 작업)

- [ ] **Step 1: `docs/API_REFERENCE.md`에 새 섹션 추가**

Task 1의 Step 9에서 제거한 "AI 추천 (job 패턴)" 섹션이 있던 자리(파일 끝)에 다음 섹션을 추가:

```markdown
## 비전 AI 분석 결과 CSV 다운로드

지자체 유휴재산 파일을 업로드하면 AI 서버가 Node 0~5(파싱→필터→지오코딩→피처수집→조례필터→비전AI분석)까지 동기로 실행하고, 결과 전체를 CSV 파일로 바로 내려준다. 채점(Node 6~7)은 하지 않는다. 저장/이력 없이 한 번 요청하면 그 응답이 곧 결과다.

### POST /vision-analysis/csv
`multipart/form-data`: `file`(필수), query `limit`(선택, 기본 3)

로그인 불필요. AI 서버가 지오코딩·피처수집·비전분석을 순서대로 실행하므로 응답까지 수십 초~수 분 걸릴 수 있다(서버 타임아웃 15분).

성공 응답: `Content-Type: text/csv`, `Content-Disposition: attachment; filename="vision_analysis_result.csv"`인 CSV 파일 바이트.

실패 응답 (AI 서버 에러를 그대로 전달):
```json
{ "success": false, "message": "CSV 파일만 업로드할 수 있습니다." }
```
```

- [ ] **Step 2: Commit**

```bash
git add docs/API_REFERENCE.md
git commit -m "docs: POST /vision-analysis/csv API 문서 추가"
```
