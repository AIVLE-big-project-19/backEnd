# 익명 추천 job 자동 정리 설계 문서

## 배경

`DELETE /recommendations/{id}`를 로그인 필수로 바꾸면서(`docs/superpowers/specs/2026-07-28-recommendation-delete-design.md` 최종 리뷰에서 지적된 무차별 삭제 취약점 대응), 익명(로그인 안 한 상태로 올린) job은 API로 아무도 지울 수 없게 됐다. 이대로면 익명 job이 DB에 계속 쌓이므로, 오래된 익명 job을 자동으로 정리하는 배치가 필요하다.

## 범위

**포함:**
- 생성된 지 24시간 지난 익명(`user IS NULL`) job과 그 하위 `RecommendationItem`을 1시간마다 자동 삭제

**제외:**
- 로그인 사용자의 job — 상태/기간과 무관하게 절대 정리 대상 아님 (본인이 `/recommendations/me`에서 직접 관리)
- AI 서버에 취소 요청 — 여전히 그런 API가 없음(기존 `DELETE` 설계와 동일한 제약)

## 핵심 결정사항

| 결정 | 선택 | 근거 |
|---|---|---|
| 보관 기간 | 24시간 | 테스트/시연용 업로드가 대부분이라 그 이상 남겨둘 이유가 적음 |
| 실행 주기 | 1시간마다 (`@Scheduled(fixedRate = 3_600_000)`) | 24시간 보관기간 대비 충분히 촘촘하고, 부하도 거의 없음 |
| 상태 무관 삭제 | QUEUED/RUNNING이어도 24시간 지났으면 삭제 | 그 시점이면 사실상 방치된 job(AI 서버가 재시작됐거나 응답이 없는 경우)으로 간주 |
| 삭제 방식 | 대상 job들을 한 번에 조회 → `deleteByJobIn`/`deleteAll`로 일괄 삭제 | 건별 순회(반복 호출)보다 코드가 단순함. 단, `deleteByJobIn`/`deleteAll`은 Spring Data가 엔티티별로 `DELETE`를 여러 번 실행하는 방식이라 실제 SQL 단일문(벌크) 삭제는 아니다 — 지금 규모에서는 무관하지만, 정리 대상이 아주 많아지면 `@Modifying` JPQL 삭제로 바꾸는 게 나을 수 있음 |
| 스케줄링 활성화 | `DemoApplication`에 `@EnableScheduling` 추가 | 이 프로젝트에서 `@Scheduled`를 쓰는 첫 사례 |

## 컴포넌트 설계

### `DemoApplication` (기존 파일 수정)

`@EnableScheduling` 어노테이션 추가.

### `RecommendationJobRepository` (기존 파일 수정)

```java
List<RecommendationJob> findByUserIsNullAndCreatedAtBefore(LocalDateTime cutoff);
```

### `RecommendationItemRepository` (기존 파일 수정)

```java
void deleteByJobIn(List<RecommendationJob> jobs);
```

### `RecommendationCleanupScheduler` (신규, `recommend.service`)

```java
@Component
public class RecommendationCleanupScheduler {

    private static final long RETENTION_HOURS = 24;

    private final RecommendationJobRepository jobRepository;
    private final RecommendationItemRepository itemRepository;

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
        log.info("만료된 익명 job {}건 정리", expired.size());
    }
}
```

`@Component`(서비스 로직이라기보단 인프라성 스케줄러라 `@Service`보다 `@Component`가 더 적절)로 두고, 기존 `RecommendService`와는 별도 클래스로 분리한다 — 요청 기반 비즈니스 로직과 배치성 정리 로직은 관심사가 달라서 한 클래스에 두지 않는다.

## 명시적 경계 사항

- **최초 배포 직후 1시간 동안**: 스케줄러가 첫 실행되기 전까지는 새로 추가된 정리 대상이 없으므로 문제 없음.
- **애플리케이션 재시작 시**: 스케줄 타이머가 리셋되므로 재시작 직후 최대 1시간까지 지연될 수 있음 — 허용 가능한 수준.
- **동시 실행 걱정 없음**: `fixedRate` 스케줄은 싱글 인스턴스 배포를 전제로 하며(현재 배포 구조가 이러함), 여러 인스턴스로 스케일아웃하면 중복 실행 가능성이 생기지만 삭제 작업은 멱등(이미 지워진 job은 다음 조회에 안 걸림)이라 실질적 위험은 없음.
- **폴링 중인 익명 job이 삭제 시점과 겹치는 경우**: 24시간 넘게 QUEUED/RUNNING으로 방치된 익명 job을 누군가 그 순간에 폴링 중이면, 이미 삭제된 job에 대해 `jobRepository.save(job)`이 새 id로 재삽입되거나(`RecommendService`가 폴링 결과를 반영하려 시도), 완료(`done`) 응답이 마침 그 타이밍에 오면 `RecommendationItem` 삽입이 FK 위반으로 그 폴링 1회만 500이 날 수 있다. 다음 폴링은 정상적으로 404(`RECOMMENDATION_JOB_NOT_FOUND`)로 정리된다. 발생 확률이 극히 낮고(24시간 넘게 안 끝난 job을 하필 그 순간에 폴링) 자연 치유되는 문제라 별도 방어 로직은 만들지 않는다.

## 테스트

- **`RecommendationCleanupSchedulerTest`** (신규, Mockito): `jobRepository.findByUserIsNullAndCreatedAtBefore`가 빈 리스트를 반환하면 삭제 메서드들이 호출 안 되는지, 대상이 있으면 `deleteByJobIn` → `deleteAll` 순서로 호출되는지
- **`RecommendationRepositoryTest`** (기존 파일에 케이스 추가, 실제 H2): 24시간 지난 익명 job은 조회되고, 최근 익명 job과 로그인 사용자 job(기간 무관)은 조회 안 되는지
