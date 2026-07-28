# 추천 이력 목록 조회 설계 문서

## 배경

로그인한 사용자가 자신이 올린 태양광 후보지 추천 job들을 목록으로 확인할 방법이 없었다. 현재 `recommend` 모듈에는 `POST /recommendations`(등록)와 `GET /recommendations/{id}`(단건 상태 조회)만 있고, "지금까지 내가 올린 job 목록"을 보는 API가 없다. 처음 설계할 때는 "방금 올린 job 하나 추적"만 필요해서 일부러 범위 밖으로 뺐던 부분이다.

## 범위

**포함:**
- 로그인한 사용자의 최근 job 10건을 요약 정보로 반환하는 `GET /recommendations/me`

**제외:**
- 페이지네이션/무한스크롤 — 10건 고정. 필요해지면 후속 과제
- 관리자용 전체 목록 조회 — 이번 범위는 "내 이력"만
- 목록 항목에 `funnel`/`recommendations` 전체 상세 포함 — 상세는 기존 `GET /{id}`로 유도

## 핵심 결정사항

| 결정 | 선택 | 근거 |
|---|---|---|
| 엔드포인트 이름 | `GET /recommendations/me` | `dashboard`의 `/analyses/me`, 기존 `/users/me`와 동일한 "내 것" 네이밍 컨벤션 |
| 비로그인 처리 | 서비스 호출 전에 컨트롤러에서 `ApiResponse.fail(...)`로 즉시 응답 | `DashboardController.history()`와 동일한 기존 패턴 그대로 재사용 |
| 반환 건수 | 최근 10건 고정 | `dashboard`의 `findTop10ByUser_IdOrderByCreatedAtDesc`와 동일 패턴 재사용, 페이지네이션은 YAGNI |
| 응답 필드 | `id`, `originalFilename`, `status`, `stage`, `errorMessage`, `createdAt` (요약) | `funnel`/`recommendations`는 이미 있는 `GET /{id}`로 상세 조회 유도. `stage`/`errorMessage`는 이미 로드되는 컬럼이라 추가 조회 없이 포함 — 목록에서 실패 사유를 바로 보여줄 수 있음 |

## 컴포넌트 설계

### `RecommendationJobRepository` (기존 파일 수정)

```java
List<RecommendationJob> findTop10ByUser_IdOrderByCreatedAtDesc(Long userId);
```

### `RecommendationHistoryResponse` (신규 DTO, `recommend.dto`)

```java
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

### `RecommendService` (기존 파일 수정)

```java
public List<RecommendationHistoryResponse> getHistory(Long userId) {
    return jobRepository.findTop10ByUser_IdOrderByCreatedAtDesc(userId).stream()
            .map(job -> new RecommendationHistoryResponse(
                    job.getId(), job.getOriginalFilename(), job.getStatus().name(),
                    job.getStage(), job.getErrorMessage(), job.getCreatedAt()))
            .toList();
}
```

기존 `submit`/`getStatus`와 달리 AI 서버 호출이 없는 단순 DB 조회라 트랜잭션 경계 문제가 없다 (읽기 전용, `@Transactional(readOnly = true)` 정도만 있으면 충분).

### `RecommendationController` (기존 파일 수정)

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

`GET /recommendations/me`는 `GET /recommendations/{id}`보다 먼저든 나중이든 매핑 순서와 무관하게 Spring MVC가 고정 경로("me")를 경로변수({id})보다 우선 매칭하므로 라우팅 충돌 없음.

## API 응답 예시

```jsonc
{
  "success": true,
  "message": "추천 이력 조회 성공",
  "data": [
    { "id": 17, "originalFilename": "대전광역시_유휴공간.xlsx", "status": "DONE", "stage": null, "errorMessage": null, "createdAt": "2026-07-28T14:16:38" },
    { "id": 16, "originalFilename": "청주시_유휴공간.xlsx", "status": "FAILED", "stage": null, "errorMessage": "AI 서버가 재시작되어 이전 작업 기록이 사라졌습니다. 파일을 다시 업로드해주세요.", "createdAt": "2026-07-28T13:57:51" }
  ]
}
```

비로그인:
```jsonc
{ "success": false, "message": "로그인 후 추천 이력을 조회할 수 있습니다.", "data": null }
```

## 명시적 경계 사항

- **10건 초과분은 조회 불가**: 페이지네이션이 없으므로 11번째 이후 job은 이 API로 볼 수 없다. 필요해지면 후속 과제.
- **익명(비로그인) 업로드 job은 이 목록에 절대 안 나옴**: `user`가 null인 job은 애초에 어떤 로그인 사용자와도 연결이 안 되므로 목록화 대상이 아니다 (기존 동작과 일치).
- **다른 모듈 변경 없음**: `report`/`dashboard`/`SecurityConfig` 등 이번 범위에서 손대지 않는다.

## 테스트

기존 컨벤션(Mockito 단위 테스트 + `@WebMvcTest` + 실제 H2 `@DataJpaTest`) 그대로.

- **`RecommendationRepositoryTest`** (기존 파일에 케이스 추가): 같은 사용자의 job이 11개일 때 최근 10개만, `createdAt` 내림차순으로 반환되는지
- **`RecommendServiceTest`** (기존 파일에 케이스 추가): `getHistory`가 리포지토리 결과를 올바른 필드로 매핑하는지
- **`RecommendationControllerTest`** (기존 파일에 케이스 추가): 로그인 상태에서 `currentUserId()`가 서비스에 전달되는지, 비로그인 상태에서 서비스 호출 없이 `fail` 응답이 오는지
