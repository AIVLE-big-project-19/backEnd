# AI 추천 서버(job 패턴) 연동 설계 문서

## 배경

지자체 유휴재산 엑셀을 업로드하면 AI 서버(FastAPI, 별도 저장소)가 파싱→필터→지오코딩→피처수집→조례검증→비전분석→ML스코어링 파이프라인을 돌려 태양광 후보지 추천 목록(`funnel` + `recommendations[]`)을 돌려준다. React는 AI 서버를 직접 호출하지 않고 항상 Spring Boot가 중계한다.

후보지 1건당 30초~2분, 전체 처리 시 5~8분 이상 걸릴 수 있어 동기 호출은 타임아웃 문제가 크다. AI 서버는 이를 위해 비동기 job 패턴(`POST /recommend/jobs` 등록 → `GET /recommend/jobs/{job_id}` 폴링)을 구현 중이며, 이번 작업은 Spring Boot 쪽을 이 job 패턴에 맞춰 연동한다.

## 범위

**포함:**
- `recommend` 모듈 신규 생성 (client / service / controller / entity / repository)
- 엑셀 업로드 → AI `POST /recommend/jobs` 등록 → 내부 job 레코드 생성
- 상태 조회(폴링) → AI `GET /recommend/jobs/{id}` 대리 조회 → 완료/실패 시 DB 반영
- AI 서버 에러(등록 실패, job 소실, 일시적 장애) 처리
- 관련 설정값(`application.yaml`) 추가

**제외 (후속 과제):**
- job 이력 목록 조회 API (마이페이지 등) — 지금은 "방금 올린 job 하나 추적"만 필요
- 동기 `POST /recommend` 및 단계별 디버깅 엔드포인트(`/parse`, `/filter` 등) 연동 — job 패턴만 연동
- 백그라운드 스케줄러(`@Scheduled`) 기반 자동 폴링 — 지금은 React가 폴링할 때만 대리 조회 (YAGNI, 근거는 "핵심 결정사항" 참고)
- 기존 `report`/`dashboard` 모듈 변경 — DTO(`AiAnalysisResponse` 등)만 재사용하고 그 외 로직은 그대로 둔다
- 건물형(`grade: null`) 결과의 프론트 표시 방침 — 프론트/기획 영역

## 핵심 결정사항

| 결정 | 선택 | 근거 |
|---|---|---|
| HTTP 클라이언트 | 기존 `RestClient` 패턴 재사용, 이 연동 전용 짧은 타임아웃(30초) 빈 1개 추가 | job 등록/폴링 모두 즉시 응답하는 짧은 호출이라 WebClient 등 새 의존성 도입 불필요. `GoogleOAuthClient`와 동일 패턴 |
| 동시성 모델 | React가 폴링할 때만 그 자리에서 AI 서버에 대리 조회("패스스루"). 백그라운드 스케줄러 없음 | 스레드/동시성 관리 복잡도 회피. "아무도 안 물어보면 저장 안 됨" 트레이드오프는 지금은 진행률을 보며 기다리는 시연용 플로우라 문제 안 됨. 자동 이력 적재가 필요해지면 그때 스케줄러 추가(기존 대리조회 로직 재사용) |
| 결과 저장 시점 | 폴링 응답이 `done`으로 바뀌는 걸 목격한 시점에 저장 | AI 서버 문서의 "done을 받으면 즉시 저장 권장"을 그대로 만족 |
| 완료/실패 job 재조회 | DB에 이미 DONE/FAILED로 저장된 job은 AI 서버를 다시 호출하지 않고 DB 값만 반환 | 끝난 job을 매번 다시 물어볼 이유가 없음. AI 서버 부하도 줄임 |
| 데이터 모델 | `RecommendationJob`(1건 업로드) + `RecommendationItem`(추천 항목 1개당 1행) | 목록/필터에 필요한 최소 컬럼만 정규화, 나머지는 JSON 통째로 저장 (아래 상세) |
| 상세 payload 직렬화 | 신규 DTO 없이 기존 `report.dto.AiAnalysisResponse` 재사용 | 그 DTO가 `1_site_info`~`5_pre_investigation_checklist` 구조를 이미 정확히 매핑하고 있음. report 모듈 동작은 안 건드리고 클래스만 import |
| 인증 요구사항 | 로그인 불필요(permitAll). 로그인 상태면 userId를 함께 저장 | `dashboard` 모듈의 `SiteAnalysis`/`currentUserId()` 패턴과 동일 |
| 폴링 404(job 소실) | 예외로 던지지 않고 job을 FAILED로 확정, 안내 메시지 저장 후 200 정상 응답 | AI 서버 재시작 시 job 기록이 인메모리라 사라지는 게 정상 동작이므로, 우리 API 입장에서는 에러가 아니라 상태 전이 |
| 폴링 중 일시적 장애(네트워크/타임아웃) | DB 상태 변경 없이 마지막 상태 그대로 반환, 서버 로그에만 경고 | 한 번의 폴링 실패로 진행 중인 job을 FAILED로 확정하면 안 됨. 다음 폴링에서 재시도 |

## 전체 흐름

```
React → Spring: POST /recommendations (엑셀 파일, limit)
Spring → AI:    POST /recommend/jobs?limit=N
Spring: RecommendationJob(QUEUED) 저장 + AI의 job_id 보관
Spring → React: 내부 job id 반환

React → Spring: GET /recommendations/{id}  (10~30초 주기 폴링)
  이미 DONE/FAILED면 DB만 보고 바로 반환 (AI 재호출 없음)
  아니면 Spring → AI: GET /recommend/jobs/{externalJobId}
    - done   → RecommendationItem 저장, 상태 DONE, 결과 반환
    - failed → errorMessage 저장, 상태 FAILED
    - 404    → 상태 FAILED + "AI 서버 재시작" 안내 메시지
    - queued/running → stage만 갱신
    - 그 외 일시적 오류 → DB 변경 없이 마지막 상태 그대로 반환 (로그만 남김)
```

## 데이터 모델

### `RecommendationJob` (신규 엔티티, `recommend.entity`)

| 필드 | 타입 | 설명 |
|---|---|---|
| id | Long | 내부 PK (API로 노출되는 id) |
| externalJobId | String, unique | AI 서버의 `job_id` (폴링용, API 밖으로는 노출 안 함) |
| user | `User`, nullable FK | 비로그인이면 null |
| originalFilename | String | 업로드 엑셀 파일명 |
| limitParam | int | 요청 시 `limit` 값 |
| status | Enum(`QUEUED`,`RUNNING`,`DONE`,`FAILED`) | AI의 status 문자열과 1:1 매핑 |
| stage | String, nullable | 진행 단계 (`node3_features` 등) |
| errorMessage | String, nullable | FAILED일 때 사유 |
| startedAt / finishedAt | LocalDateTime, nullable | AI 응답 값 그대로 저장 |
| funnelJson | TEXT, nullable | DONE일 때 funnel 통계를 JSON 문자열로 저장 |
| createdAt | (`BaseEntity` 상속) | 등록 시각 |

### `RecommendationItem` (신규 엔티티, `recommend.entity`)

DONE된 job의 `recommendations[]` 배열 항목 하나당 1행.

| 필드 | 타입 | 설명 |
|---|---|---|
| id | Long | 내부 PK |
| job | `RecommendationJob`, FK | 소속 job |
| targetType | String | `LAND` / `BUILDING` |
| siteId, address, grade, totalScore, priorityRank, status | 개별 컬럼 | 목록/정렬/필터용 |
| payload | TEXT | 항목 전체를 `AiAnalysisResponse`로 직렬화한 JSON |

개별 컬럼을 최소화하고 나머지(비전 분석, XAI 근거, 체크리스트 등 30개 이상 필드)를 JSON으로 뭉쳐서 저장하는 이유: AI 파이프라인 스키마가 계속 바뀌는 중(문서에도 "미구현", "스키마 유지용" 필드가 다수)이라 필드마다 컬럼화하면 AI 쪽이 바뀔 때마다 DB 마이그레이션이 따라가야 한다.

## 컴포넌트 설계

### `RecommendClient` (신규, `recommend.client`)

`RestClient` 기반. 이 연동 전용으로 connect/read 타임아웃을 짧게(기본 30초) 잡은 별도 `RestClient` 빈을 사용한다 (기존 `AiAnalysisClient`가 쓰는 무제한 타임아웃 클라이언트는 그대로 둠).

```java
JobSubmitResult submitJob(MultipartFile file, int limit);   // POST /recommend/jobs
JobStatusResult pollJob(String externalJobId);              // GET /recommend/jobs/{id}, 404는 null 반환
```

- `submitJob`: 실패 시 `RestClientResponseException`을 잡아 응답 바디의 `detail`을 추출, `CustomException(AI_RECOMMEND_FAILED, detail)`로 변환 (`detail` 파싱 실패 시 기본 메시지)
- `pollJob`: 404는 예외가 아니라 `null` 반환으로 표현해 호출부(`RecommendService`)가 "job 소실"과 "일시적 오류"를 구분해서 처리할 수 있게 한다. 그 외 네트워크/5xx 오류는 예외로 던져 호출부가 "DB 변경 없이 마지막 상태 유지" 처리를 하게 한다.

### `RecommendService` (신규, `recommend.service`)

```java
RecommendationJob submit(MultipartFile file, int limit, Long userId);
RecommendationJob getStatus(Long jobId);   // 폴링 진입점, 위 흐름의 분기 로직 전체를 담당
```

`getStatus`는 DB에서 job을 읽어 이미 DONE/FAILED면 즉시 반환. 아니면 `RecommendClient.pollJob` 호출 후 위 "전체 흐름"의 분기(done/failed/404/queued·running/일시적 오류)를 처리한다.

### `RecommendationController` (신규, `recommend.controller`)

```java
POST /recommendations                // multipart file, query limit(기본 3)
GET  /recommendations/{id}
```

`SecurityConfig`는 별도 매처 추가 없이 기존 `anyRequest().permitAll()`에 포함된다. userId는 `dashboard`의 `currentUserId()`와 동일하게 `SecurityContextHolder`에서 nullable로 추출.

## API 설계

**`POST /recommendations`**

```jsonc
// 200
{ "id": 17, "status": "QUEUED" }
```

**`GET /recommendations/{id}`**

```jsonc
// 진행 중
{ "id": 17, "status": "RUNNING", "stage": "node3_features" }

// 완료
{
  "id": 17, "status": "DONE",
  "funnel": { "node0_parsed": 230, "...": "..." },
  "recommendations": [ /* AiAnalysisResponse 배열 */ ]
}

// 실패 (job 소실 포함)
{ "id": 17, "status": "FAILED", "errorMessage": "AI 서버가 재시작되어 이전 작업 기록이 사라졌습니다. 파일을 다시 업로드해주세요." }
```

React는 HTTP 상태 코드로 분기할 필요 없이 항상 `status` 필드만 보면 된다 (job 소실도 200으로 내려감).

## 에러 처리

| 상황 | 처리 |
|---|---|
| job 등록 자체가 실패 (400/422/500) | `CustomException(AI_RECOMMEND_FAILED, detail)` — `GlobalExceptionHandler`가 기존 방식대로 502로 응답. job 레코드는 생성하지 않음 |
| 폴링 중 AI가 `done` | `RecommendationItem` 저장 + job DONE, 200 |
| 폴링 중 AI가 `failed` | `error` 필드를 `errorMessage`에 저장 + job FAILED, 200 |
| 폴링 중 AI가 404 | job FAILED + 고정 안내 메시지, 200 |
| 폴링 중 네트워크/5xx 등 일시적 오류 | DB 변경 없음, 마지막 저장된 상태 그대로 200 반환, 서버 로그에 경고만 남김 |

새 `ErrorCode.AI_RECOMMEND_FAILED(HttpStatus.BAD_GATEWAY, "AI 추천 서버 호출에 실패했습니다.")` 추가 (등록 실패 전용, `GoogleOAuthClient`의 `GOOGLE_AUTH_FAILED`와 동일한 패턴).

## 설정값 (`application.yaml`)

```yaml
ai:
  server:
    base-url: ${AI_SERVER_URL:http://localhost:8000}                       # 기존 키, 기본값만 8000으로 변경
    analyze-path: ${AI_SERVER_ANALYZE_PATH:/analyze}                       # 기존 값, 변경 없음
    recommend-jobs-path: ${AI_SERVER_RECOMMEND_JOBS_PATH:/recommend/jobs}  # 신규
    request-timeout-ms: ${AI_SERVER_REQUEST_TIMEOUT_MS:30000}              # 신규, 등록/폴링 공용
```

## 명시적 경계 사항

- **폴링이 끊기면 결과가 저장되지 않을 수 있다**: React가 완료 전 폴링을 완전히 멈추면 AI 서버는 job을 끝내놔도 우리 DB는 갱신되지 않는다. 지금 범위에서는 수용된 트레이드오프 (핵심 결정사항 참고).
- **AI 서버 재시작으로 인한 job 소실은 우리가 감지만 하고 자동 재제출하지 않는다**: 원본 엑셀 파일을 우리 쪽에 보관하지 않으므로, 소실 시 사용자가 다시 업로드해야 한다.
- **동기 `/recommend` 및 단계별 디버깅 엔드포인트는 이번 범위에 없다**: 필요해지면 별도 설계로 다룬다.
- **job 목록/이력 조회 API는 없다**: `GET /recommendations/{id}`로 단일 job만 조회 가능. 이력 화면이 필요해지면 `dashboard.history()`류 API를 참고해 추가.
- **`report`/`dashboard` 모듈은 동작 변경 없음**: `AiAnalysisResponse` 등 DTO 클래스만 재사용(import)하고 그 외 코드는 손대지 않는다.

## 테스트

기존 컨벤션(Mockito 단위 테스트 + `MockRestServiceServer` + `@WebMvcTest` + 실제 H2 DB 테스트) 그대로 따른다.

- **`RecommendClientTest`** (`GoogleOAuthClientTest`와 동일 패턴): 등록 성공/실패(`detail` 파싱 포함), 폴링 queued/running/done/failed/404 각각 파싱
- **`RecommendServiceTest`** (Mockito, `AuthServiceTest`류): 등록 시 QUEUED 저장 필드 검증, 이미 DONE/FAILED인 job은 AI 재호출 안 함, RUNNING→DONE 전환 시 `RecommendationItem` 저장, 404→FAILED+안내메시지, 일시적 예외 시 DB 상태 불변
- **`RecommendationControllerTest`** (MockMvc 슬라이스, `AuthApiControllerTest`류): multipart 업로드 파라미터 전달 확인, 비로그인/로그인 두 케이스에서 userId 처리 확인
- **`RecommendationRepositoryTest`** (실제 H2, `WithdrawalServiceIntegrationTest`류): `funnelJson`/`payload` TEXT 컬럼에 JSON 저장 후 재조회 시 그대로 나오는지 (이 프로젝트에서 처음 쓰는 JSON-in-TEXT 패턴이라 실제 DB로 확인)

## 문서

`docs/API_REFERENCE.md`에 `POST /recommendations`, `GET /recommendations/{id}` 엔드포인트와 상태값(`QUEUED`/`RUNNING`/`DONE`/`FAILED`) 설명 추가.
