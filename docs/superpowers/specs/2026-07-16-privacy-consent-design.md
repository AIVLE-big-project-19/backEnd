# 개인정보 수집·이용 동의 (Phase 4) 설계 문서

## 배경

컴플라이언스 요구사항(개인정보 보호법, 개인정보의 안전성 확보조치 기준, ISMS-P)에 따라:

- 개인정보 수집(회원가입) 시 **동의받는 화면**을 구현해야 한다.
- 동의 사실을 **증빙 가능한 형태(언제, 어떤 버전에, 동의/거부했는지)**로 남겨야 한다.
- 메인 서비스 화면 하단에 **개인정보처리방침**을 기재해야 한다.
- 정보주체의 **동의 철회권**(선택 동의 항목)을 보장해야 한다.

백엔드(이 저장소)는 동의 수집 API·이력 저장·약관 본문 제공을 구현하고, 프론트엔드(별도 저장소)는 `docs/API_REFERENCE.md` 갱신본을 기준으로 화면을 구현한다.

## 범위

**포함:**
- 회원가입 시 동의 3항목 수집: (필수) 서비스 이용약관, (필수) 개인정보 수집·이용, (선택) 마케팅 정보 수신
- 동의 이력 저장 (append-only, 미동의도 기록)
- 구글 자동가입 시 필수 동의 자동 기록
- 약관 본문 제공 API
- 내 동의 현황 조회 / 마케팅 동의 변경(철회 포함) API

**제외 (후속 과제):**
- 약관 개정 시 기존 동의자 재동의 강제 흐름 (버전 필드로 기반만 마련)
- 필수 동의 철회 = 회원탈퇴 흐름
- 기존 가입자 소급 마이그레이션 (동의 기록 없음 상태를 그대로 표현)
- 관리자용 약관 관리 화면 (본문은 리소스 파일, 버전은 설정값으로 관리)

## 핵심 결정사항

| 결정 | 선택 | 근거 |
|---|---|---|
| 동의 항목 | 필수 2(TERMS, PRIVACY) + 선택 1(MARKETING) | 컴플라이언스 요건 충족 + 필수/선택 구분 구현 |
| 이력 구조 | 단일 `user_consents` 테이블, append-only | 증빙 요건을 충족하는 최소 구조. UPDATE 없음 → 이력 훼손 불가 |
| 구글 자동가입 | 버튼 옆 안내 문구 + 필수 동의 자동 기록(마케팅은 false) | 플로우 단순 유지. API 계약 변경 없음 |
| 약관 본문 | 백엔드 리소스 파일 + `GET /terms/{type}` | 문구 수정 시 백엔드 배포만으로 반영, 프론트 재배포 불필요 |
| 버전 | `application.yaml`의 `terms.version` 단일 값 | 문서별 개별 버전은 YAGNI |
| 마케팅 동의 변경 | 마이페이지 API 포함 | 동의 철회권 요건 |

## 데이터 모델

### ConsentType enum (`com.example.demo.user.entity`)

```java
public enum ConsentType { TERMS, PRIVACY, MARKETING }
```

- `TERMS` = 서비스 이용약관 (필수)
- `PRIVACY` = 개인정보 수집·이용 (필수)
- `MARKETING` = 마케팅 정보 수신 (선택)

### UserConsent 엔티티 (`com.example.demo.user.entity`)

기존 컨벤션(BaseEntity 상속, `@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder`, RefreshToken과 동일한 ManyToOne 패턴)을 그대로 따른다.

| 컬럼 | 타입 | 비고 |
|---|---|---|
| id | Long | PK, IDENTITY |
| user | User (ManyToOne, LAZY) | `user_id`, nullable=false |
| consentType | ConsentType | EnumType.STRING, nullable=false, length 20 |
| agreed | boolean | nullable=false |
| termsVersion | String | nullable=false, length 20. 기록 시점의 `terms.version` 값 |
| createdAt | (BaseEntity) | 동의/철회 시각으로 사용 |

- **append-only**: UPDATE/DELETE 하지 않는다. 상태 변경 = 새 행 추가.
- **미동의도 기록**: 가입 시 마케팅 미동의면 `agreed=false` 행을 남긴다. "묻지 않았음(행 없음)"과 "명시적으로 거부했음(agreed=false)"이 구분된다.
- 현재 상태 = 타입별 최신 행(`findTopByUserAndConsentTypeOrderByIdDesc`).

### UserConsentRepository

```java
Optional<UserConsent> findTopByUserAndConsentTypeOrderByIdDesc(User user, ConsentType consentType);
```

## 약관 본문·버전 관리

- 본문: `src/main/resources/terms/TERMS.md`, `src/main/resources/terms/PRIVACY.md` (마크다운, 한국어. MARKETING은 별도 본문 없음)
- 버전: `application.yaml`에 `terms.version: "1.0"`. 동의 기록 시 이 값을 `termsVersion`에 저장.
- 약관 개정 시: 본문 파일 수정 + 버전 올려서 배포. 구버전 동의자 재동의 요구는 후속 과제.

## API 명세

응답 포맷·에러 처리·인증 방식은 기존 컨벤션(`ApiResponse`, `CustomException(ErrorCode)`, `GlobalExceptionHandler`, `@AuthenticationPrincipal Long userId`)을 그대로 따른다.

### 1. `POST /auth/signup` — 기존 API 확장

요청에 3필드 추가:

```json
{
  "loginId": "tester01", "email": "user@example.com",
  "password": "password1!", "name": "홍길동",
  "termsAgreed": true, "privacyAgreed": true, "marketingAgreed": false
}
```

- `termsAgreed`, `privacyAgreed`: `@AssertTrue(message = "필수 약관에 동의해야 합니다.")` → 미동의 시 기존 `@Valid` 패턴 그대로 400 + `data`에 필드별 메시지. **새 ErrorCode 불필요.**
- `marketingAgreed`: 선택, 기본값 `false` (미전송 허용).
- 가입 성공 시 `UserService.signup`이 **같은 트랜잭션에서** 3행 기록: TERMS `agreed=true`, PRIVACY `agreed=true`, MARKETING `agreed=요청값`.
- signup 메서드에 `@Transactional` 명시 (user 저장 + consent 3행이 원자적으로).

### 2. 구글 자동가입 — `AuthService.googleLogin` 내부 변경

- **신규 자동가입 시에만** 3행 기록: TERMS/PRIVACY `agreed=true`, MARKETING `agreed=false`.
- 기존 유저 로그인(returning Google user, 경합 복구 포함) 시에는 기록하지 않는다.
- API 계약(`{code, redirectUri}`) 변경 없음. 프론트는 구글 버튼 옆에 안내 문구 표시: "구글 로그인 시 이용약관 및 개인정보처리방침에 동의한 것으로 간주됩니다."

### 3. `GET /terms/{type}` — 신규 (permitAll)

- `type`: `TERMS` | `PRIVACY` (대소문자 무관). 그 외/매핑 불가 → 404 `TERMS_NOT_FOUND`.
- 응답: `{ "data": { "type": "TERMS", "version": "1.0", "content": "...markdown..." } }`
- 신규 `TermsController` + `TermsService`. 본문은 클래스패스 리소스에서 로딩 후 메모리 캐싱.
- 프론트 사용처: 회원가입 화면 "전문 보기" 모달, 푸터 "개인정보처리방침" 페이지.

### 4. `GET /users/me/consents` — 신규 (인증 필요)

- `@AuthenticationPrincipal Long userId` (MyPageController 패턴).
- 응답: 3개 타입 전부 포함, 기록 없는 항목은 null.

```json
{ "data": { "consents": [
  { "type": "TERMS",     "agreed": true,  "version": "1.0", "agreedAt": "2026-07-16T12:00:00" },
  { "type": "PRIVACY",   "agreed": true,  "version": "1.0", "agreedAt": "2026-07-16T12:00:00" },
  { "type": "MARKETING", "agreed": false, "version": "1.0", "agreedAt": "2026-07-16T12:00:00" }
] } }
```

- 기존 가입자(기록 없음): `{ "type": "TERMS", "agreed": null, "version": null, "agreedAt": null }`.

### 5. `PUT /users/me/consents/marketing` — 신규 (인증 필요)

- 요청: `{ "agreed": true }` (`@NotNull`)
- 동의든 철회든 새 행 append. 응답 200 + 변경된 현재 상태(4번 응답의 MARKETING 항목과 동일 형태).
- TERMS/PRIVACY 변경 API는 만들지 않는다 (필수 동의 철회 = 회원탈퇴 영역, 범위 밖).

## 코드 추가 항목

**SuccessCode**: `TERMS_FOUND("약관 조회 성공")`, `CONSENT_STATUS_FOUND("동의 현황 조회 성공")`, `MARKETING_CONSENT_UPDATED("마케팅 수신 동의가 변경되었습니다.")`

**ErrorCode**: `TERMS_NOT_FOUND(HttpStatus.NOT_FOUND, "약관을 찾을 수 없습니다.")`

## 파일 구조

```
src/main/java/com/example/demo/user/
  entity/ConsentType.java              (신규)
  entity/UserConsent.java              (신규)
  repository/UserConsentRepository.java (신규)
  service/ConsentService.java          (신규: 동의 기록/조회/변경 로직)
  service/TermsService.java            (신규: 약관 본문 로딩)
  service/UserService.java             (수정: signup에 동의 기록)
  service/AuthService.java             (수정: googleLogin 신규가입 시 동의 기록)
  controller/TermsController.java      (신규)
  controller/ConsentController.java    (신규: /users/me/consents)
  dto/SignupRequest.java               (수정: 동의 필드 3개)
  dto/TermsResponse.java               (신규)
  dto/ConsentStatusResponse.java       (신규)
  dto/MarketingConsentRequest.java     (신규)
src/main/resources/terms/TERMS.md      (신규)
src/main/resources/terms/PRIVACY.md    (신규)
```

- 동의 기록/조회 로직은 `ConsentService`로 분리해 `UserService`/`AuthService`가 공유한다 (가입 시 3행 기록 로직 중복 방지).

## 테스트

기존 TDD 컨벤션(Mockito 단위 테스트 + `@WebMvcTest`) 그대로.

- `ConsentServiceTest` (신규): 가입 동의 3행 기록(마케팅 true/false 각각), 현황 조회(기록 있음/없음), 마케팅 변경 시 append 확인
- `UserServiceTest` (수정): signup이 ConsentService를 호출하는지
- `AuthServiceTest` (수정): 구글 신규가입 시 동의 기록 호출, 기존 유저 로그인 시 미호출
- `TermsServiceTest` (신규): 본문 로딩, 없는 타입 → `TERMS_NOT_FOUND`
- `AuthApiControllerTest` (수정): 기존 signup 테스트에 동의 필드 추가, 필수 미동의 400 케이스 추가
- `TermsController`/`ConsentController` 테스트 (신규): 조회 200/404, 현황 조회, 마케팅 변경

## 문서

`docs/API_REFERENCE.md` 갱신:
- signup 요청 변경(동의 필드 3개, 필수 미동의 400)
- 신규 API 3개(약관 조회, 동의 현황, 마케팅 변경)
- 구글 버튼 안내 문구 가이드
- 화면 흐름: 회원가입 동의 체크박스(전체동의/개별, 필수 미체크 시 가입 버튼 비활성 권장), 푸터 개인정보처리방침 링크
