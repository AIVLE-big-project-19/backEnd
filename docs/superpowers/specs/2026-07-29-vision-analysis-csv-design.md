# 비전 AI 분석 결과 CSV 다운로드 설계 문서 (`/recommendations` 전체 교체)

## 배경

AI 서버 쪽 요구사항이 바뀌었다. 기존에 만든 `/recommendations`(엑셀 업로드 → 비동기 job 등록 → 폴링 → ML 채점까지 끝난 추천 결과)는 더 이상 필요 없고, 대신 **채점 없이 Node 0~5(파싱→필터→지오코딩→피처수집→조례필터→비전AI분석)까지만 실행하고 결과를 CSV 파일로 바로 다운로드**하는 훨씬 단순한 기능으로 교체하기로 결정했다. AI 서버에 이미 이 용도의 엔드포인트(`POST /vision/csv`)가 구현돼 있다(`ai-agent` 저장소 `main.py:276-324`, `git log` 커밋 `3ffbb99`).

## 범위

**포함:**
- `/recommendations` 및 관련 코드(엔티티/리포지토리/서비스/컨트롤러/스케줄러/테스트) 전체 삭제
- `POST /vision-analysis/csv` 신규 추가 — AI 서버 `/vision/csv`를 그대로 프록시하는 일회성(stateless) 엔드포인트

**제외:**
- DB 저장, 이력 조회, 폴링 — 전부 필요 없어짐 (일회성 업로드→다운로드)
- ML 채점/등급/순위 — AI 서버의 `/vision/csv` 자체가 Node 6~7을 실행하지 않음

## 핵심 결정사항

| 결정 | 선택 | 근거 |
|---|---|---|
| 기존 기능 처리 | `recommend` 패키지 전체 삭제 (엔티티/리포지토리/서비스/컨트롤러/스케줄러/테스트) | 요구사항이 완전히 바뀌어서 점진적 수정보다 새로 만드는 게 깔끔함 |
| 새 엔드포인트 경로 | `POST /vision-analysis/csv` | `recommend`와 무관한 완전히 별개 기능임을 이름으로 명확히 함 |
| 인증 | 불필요 (permitAll) | 저장도 안 하고 파일 하나 받아서 넘겨주는 것뿐이라 막을 이유가 없음 |
| 응답 방식 | AI 서버가 준 CSV 바이트를 그대로 스트리밍 | 저장/파싱 없이 순수 프록시. `ReportController.getPdf`가 이미 쓰는 `ResponseEntity<byte[]>` + `Content-Disposition` 패턴 재사용 |
| 타임아웃 | 신규 15분짜리 `RestClient` 빈 | `/vision/csv`는 Node 0~5(지오코딩+피처수집+비전분석) 전체를 동기로 실행해서 후보지당 수십초~수분 걸림. 기존 30초 타임아웃(job 등록/폴링용)으로는 부족 |
| 에러 처리 | AI 서버 에러 응답의 `detail`을 그대로 `CustomException` 메시지로 사용, 기존 `GlobalExceptionHandler`가 JSON으로 응답 | 성공 시엔 파일 바이트, 실패 시엔 JSON — `ReportController`도 이미 응답 형태가 성공/실패로 다름 |

## 컴포넌트 설계

### 삭제 대상

- `src/main/java/com/example/demo/recommend/**` (client, client/dto, controller, dto, entity, repository, service)
- `src/test/java/com/example/demo/recommend/**`
- `SecurityConfig`: `.requestMatchers(HttpMethod.DELETE, "/recommendations/*").authenticated()` 줄 제거
- `DemoApplication`: `@EnableScheduling` 및 관련 import 제거 (이 프로젝트에서 그거 쓰는 유일한 스케줄러가 삭제 대상이라 전체가 불필요해짐)
- `ErrorCode`: `AI_RECOMMEND_FAILED`, `RECOMMENDATION_JOB_NOT_FOUND` 제거
- `SuccessCode`: `RECOMMENDATION_SUBMITTED`, `RECOMMENDATION_STATUS_FOUND`, `RECOMMENDATION_HISTORY_FOUND`, `RECOMMENDATION_DELETED` 제거
- `application.yaml`: `ai.server.recommend-jobs-path`, `ai.server.request-timeout-ms` 제거 (`base-url`/`analyze-path`는 `report` 모듈이 계속 쓰므로 유지)
- `RestClientConfig`: `timeoutBoundRestClientBuilder` 빈 제거
- `docs/API_REFERENCE.md`: "AI 추천 (job 패턴)" 섹션 제거

### `RestClientConfig` (기존 파일 수정)

`timeoutBoundRestClientBuilder` 대신, 훨씬 긴 타임아웃의 새 빈 추가:

```java
@Bean
public RestClient.Builder visionCsvRestClientBuilder(
        @Value("${ai.server.vision-csv-timeout-ms}") long timeoutMs
) {
    HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofMillis(timeoutMs))
            .build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(Duration.ofMillis(timeoutMs));

    return RestClient.builder().requestFactory(requestFactory);
}
```

`.version(HttpClient.Version.HTTP_1_1)`은 이전에 AI 서버(uvicorn/h11)가 JDK HttpClient의 cleartext HTTP/2 업그레이드 시도를 거부해서 겪었던 문제(`051ddcd` 커밋) 재발 방지 — 이번에도 같은 AI 서버를 호출하므로 동일하게 필요.

### `VisionAnalysisClient` (신규, `com.example.demo.visionanalysis.client`)

```java
public byte[] fetchVisionAnalysisCsv(MultipartFile file, int limit) {
    // POST {base-url}{vision-csv-path}?limit=... 로 멀티파트 전달
    // 성공: 응답 바이트(CSV) 그대로 반환
    // 실패: RestClientResponseException에서 detail 추출 -> CustomException(VISION_ANALYSIS_FAILED, detail)
}
```

기존 `RecommendClient`의 `extractDetail` 패턴(`JsonNode`로 `detail` 필드 파싱, 실패 시 기본 메시지)을 그대로 재사용한다.

### `VisionAnalysisController` (신규, `com.example.demo.visionanalysis.controller`)

```java
@PostMapping("/csv")
public ResponseEntity<byte[]> downloadCsv(
        @RequestParam("file") MultipartFile file,
        @RequestParam(defaultValue = "3") int limit
) {
    byte[] csv = visionAnalysisClient.fetchVisionAnalysisCsv(file, limit);
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.parseMediaType("text/csv"));
    headers.setContentDispositionFormData("attachment", "vision_analysis_result.csv");
    return new ResponseEntity<>(csv, headers, HttpStatus.OK);
}
```

`@RequestMapping("/vision-analysis")`로 클래스에 경로 지정. 에러는 `VisionAnalysisClient`가 던지는 `CustomException`이 그대로 위로 전파되어 `GlobalExceptionHandler`가 처리 — 컨트롤러에 별도 try/catch 불필요.

### `ErrorCode` 추가

```java
VISION_ANALYSIS_FAILED(HttpStatus.BAD_GATEWAY, "비전 분석 서버 호출에 실패했습니다.")
```

### `application.yaml` 추가

```yaml
ai:
  server:
    vision-csv-path: ${AI_SERVER_VISION_CSV_PATH:/vision/csv}
    vision-csv-timeout-ms: ${AI_SERVER_VISION_CSV_TIMEOUT_MS:900000}   # 15분
```

## 명시적 경계 사항

- **파일 크기·업로드 용량 제한은 기존 `spring.servlet.multipart.max-file-size`(20MB) 그대로 적용됨** — 별도 설정 불필요.
- **AI 서버가 15분 넘게 응답 안 하면 타임아웃 예외** → `VISION_ANALYSIS_FAILED`로 처리됨.
- **저장/이력 없음**: 같은 파일을 다시 올려서 다시 받는 것 외에 "방금 받은 결과 다시 보기" 같은 기능은 없음 (요구사항에 없으므로 YAGNI).
- **프론트 영향**: 기존 `/recommendations`, `/recommendations/me`, `/recommendations/{id}`를 쓰던 프론트 코드는 전부 새 엔드포인트로 다시 연동해야 함 — 프론트 쪽에 별도 전달 필요.

## 테스트

- **`VisionAnalysisClientTest`** (`MockRestServiceServer`, 기존 `RecommendClientTest` 패턴): 성공 시 CSV 바이트 그대로 반환, 400/500 에러 시 `detail` 메시지를 담은 `CustomException` 발생
- **`VisionAnalysisControllerTest`** (`@WebMvcTest`): 성공 시 `Content-Type: text/csv` + 파일 바이트 응답, 클라이언트가 예외를 던지면 `GlobalExceptionHandler`를 통해 JSON 에러 응답(`{success:false, message}`)이 나오는지
