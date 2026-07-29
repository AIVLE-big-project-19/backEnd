# 추천 이력 삭제 설계 문서

## 배경

프론트팀이 `DELETE /recommendations/{id}`를 요청했다 (`recommendationApi.js`에 `deleteRecommendation`이 이미 연동 대기 중). 사용자가 자신의 추천 job 이력을 목록에서 지울 방법이 없었다.

## 범위

**포함:**
- `DELETE /recommendations/{id}` — 해당 job과 그 하위 `RecommendationItem`들을 삭제

**제외:**
- AI 서버에 취소 요청을 보내는 것 — AI 서버에는 job 취소/삭제 API가 없다 (§2 문서 기준). 이미 등록된 job은 AI 서버 쪽에서 계속 처리되다가 완료되지만, 우리 DB에는 더 이상 기록이 없으므로 그 결과는 그냥 버려진다. 허용된 트레이드오프.
- 소프트 삭제 — 완전 삭제(hard delete)만 지원.

## 핵심 결정사항

| 결정 | 선택 | 근거 |
|---|---|---|
| 응답 포맷 | 200 + `ApiResponse.success(RECOMMENDATION_DELETED)` | `DELETE /boards/{id}`, `DELETE /comments/{id}`와 동일 (204 아님) — 프론트가 이미 이 포맷으로 연동됨 |
| 인증 요구사항 | **불필요** — `GET /{id}`와 동일하게 permitAll | 처음엔 board/comment처럼 로그인 필수로 설계했으나, 최종적으로 기존 조회 API와 동일한 정책으로 결정 (사용자 확정 요청) |
| 권한 체크 | `getStatus`와 완전히 동일한 로직: `job.getUser() != null && !job.getUser().getId().equals(requesterUserId)` 면 `RECOMMENDATION_JOB_NOT_FOUND` | 소유자가 있는 job은 그 사용자만, 익명(owner 없음) job은 로그인 여부와 무관하게 누구나 삭제 가능 — 조회 정책과 대칭 |
| 에러 코드 | 존재하지 않는 id, 소유자 불일치 모두 동일하게 `RECOMMENDATION_JOB_NOT_FOUND` | 존재 자체를 노출하지 않는 기존 원칙 재사용 |
| 삭제 순서 | `RecommendationItem` 먼저 삭제 → `RecommendationJob` 삭제 | `RecommendationItem.job`이 FK(`nullable=false`)라 부모를 먼저 지우면 제약 위반. `WithdrawalService`가 이미 쓰는 수동 순서 삭제 패턴과 동일 |

## 컴포넌트 설계

### `RecommendationItemRepository` (기존 파일 수정)

```java
void deleteByJob(RecommendationJob job);
```

### `RecommendService` (기존 파일 수정)

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

AI 서버 호출이 없는 단순 DB 작업이라 (`getHistory`와 동일한 이유로) 평범한 `@Transactional`을 쓴다 — 다른 메서드들의 `TransactionTemplate` 분리는 필요 없다.

### `RecommendationController` (기존 파일 수정)

```java
@DeleteMapping("/{id}")
public ApiResponse<Void> delete(@PathVariable Long id) {
    recommendService.delete(id, currentUserId());
    return ApiResponse.success(SuccessCode.RECOMMENDATION_DELETED);
}
```

`SecurityConfig`는 수정하지 않는다 — `/recommendations/**`에 매처가 없어 기존 `anyRequest().permitAll()`에 그대로 포함된다.

## API 응답 예시

```jsonc
// 성공
{ "success": true, "message": "추천 이력이 삭제되었습니다.", "data": null }

// 존재하지 않거나 남의 job (구분 없이 동일)
{ "success": false, "message": "요청한 추천 작업을 찾을 수 없습니다.", "data": null }
```

## 명시적 경계 사항

- **AI 서버에 진행 중인 job을 취소하지 않는다**: QUEUED/RUNNING 상태인 job을 지워도 AI 서버는 계속 처리한다. 완료돼도 우리 DB에 저장할 대상이 없으니 결과는 버려진다.
- **`report`/`dashboard`/`SecurityConfig` 변경 없음**.

## 테스트

- **`RecommendationRepositoryTest`**: `deleteByJob` 호출 후 item이 실제로 삭제되는지, 그 다음 job도 정상 삭제되는지 (FK 순서 확인) 실제 H2로 검증
- **`RecommendServiceTest`**: 소유자 일치 시 삭제, 소유자 불일치 시 `RECOMMENDATION_JOB_NOT_FOUND`, 익명 job은 비로그인(`requesterUserId=null`)도 삭제 가능, 존재하지 않는 id도 동일 에러
- **`RecommendationControllerTest`**: `DELETE /{id}` 호출 시 서비스에 id/userId가 올바르게 전달되는지
