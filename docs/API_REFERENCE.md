# 로그인/회원가입 API 레퍼런스 (Phase 1 + 2 + 3 + 4)

프론트엔드 개발 시 참고용 문서입니다. `src/main/java/com/example/demo/user/` 실제 구현 코드 기준으로 작성했습니다 (초기 설계 문서와 일부 다른 부분 있음 — JWT 방식으로 전환됨).

## 기본 정보

- Base URL: `http://localhost:8080/api` (배포 도메인은 별도 안내)
- 인증 방식: **세션/쿠키 아님. JWT.** 로그인 성공 시 `accessToken`/`refreshToken`을 응답 body로 받아서 프론트가 직접 보관.
  - `accessToken`은 이후 요청 시 `Authorization: Bearer {accessToken}` 헤더로 실어 보냄 (단, 이번 Phase에서는 모든 엔드포인트가 아직 `permitAll` 상태라 안 보내도 요청은 통과합니다. 다음 Phase에서 잠길 예정이므로 미리 붙여서 개발하는 걸 권장).
  - `accessToken`은 메모리 보관, `refreshToken`은 "로그인상태유지" 체크 여부에 따라 보관 방식을 다르게(체크 시 localStorage 등 영속 저장, 미체크 시 sessionStorage) 가져가는 걸 권장.
- 모든 응답 공통 포맷:
  ```json
  { "success": true, "message": "...", "data": { ... } }
  ```
  실패 시 `success: false`, `data: null`, `message`에 사용자용 에러 메시지.

## 엔드포인트

### 1. 아이디 중복확인
`GET /auth/check-login-id?value={아이디}`

응답: `{ "data": { "available": true } }`

### 2. 이메일 인증코드 발송
`POST /auth/email/send-code`
```json
{ "email": "user@example.com" }
```
- 성공: 200
- 실패: `EMAIL_CODE_COOLDOWN` (429) — 1분 이내 재발송 요청 시
- 발송된 코드는 5분간 유효, 실패 5회 시 코드 무효화됨(재발송 필요)

### 3. 이메일 인증번호 확인
`POST /auth/email/verify-code`
```json
{ "email": "user@example.com", "code": "123456" }
```
- 성공: 200 (내부적으로 인증완료 플래그가 30분간 유지되며, 이 상태에서만 회원가입 가능)
- 실패: `INVALID_VERIFICATION_CODE` (400) — 불일치/만료/시도횟수초과

### 4. 회원가입
`POST /auth/signup`
```json
{
  "loginId": "tester01",
  "email": "user@example.com",
  "password": "password1!",
  "name": "홍길동",
  "termsAgreed": true,
  "privacyAgreed": true,
  "marketingAgreed": false
}
```
- 성공: **201**
- 실패:
  - `EMAIL_VERIFICATION_REQUIRED` (400) — 3번 인증을 먼저 안 했으면
  - `DUPLICATE_LOGIN_ID` (409)
  - `DUPLICATE_EMAIL` (409)
  - 비밀번호 형식 불일치 (400) — 아래 검증 규칙 참고
  - 필수 약관 미동의 (400) — `termsAgreed`/`privacyAgreed`가 `true`가 아니면. `data`에 `{"termsAgreed": "필수 약관에 동의해야 합니다."}` 형태로 내려옴
- 검증 규칙: loginId 4~20자, password **8~16자이며 영문/숫자/특수문자를 모두 포함**, email 형식, 전부 필수. `termsAgreed`(이용약관)/`privacyAgreed`(개인정보 수집·이용)는 **필수 동의**, `marketingAgreed`(마케팅 수신)는 선택(미전송 시 false)
- 동의 화면 구현 가이드: 전체동의 체크박스 + 항목별 체크박스(필수 2개 + 선택 1개), 필수 미체크 시 가입 버튼 비활성화 권장. 각 항목의 "전문 보기"는 15번 약관 조회 API로 본문을 불러와 모달 등으로 표시

### 5. 로그인
`POST /auth/login`
```json
{ "loginId": "tester01", "password": "password123", "rememberMe": true }
```
응답: `{ "data": { "accessToken": "...", "refreshToken": "..." } }`
- 실패: `INVALID_CREDENTIALS` (401) — 아이디/비번 중 뭐가 틀렸는지는 구분 안 해줌(보안상 의도된 동작)
- 실패: `ACCOUNT_LOCKED` (**423**) — 비밀번호 5회 오입력 시 계정이 일시 잠금됩니다. 잠금 시간은 1번째 5분 → 2번째 15분 → 3번째부터 30분으로 늘어나며, 시간이 지나면 자동 해제됩니다. `message`에 남은 시간이 포함됩니다(예: "로그인 시도 횟수를 초과하여 계정이 일시적으로 잠겼습니다. 12분 후 다시 시도해주세요."). **잠금 중에는 올바른 비밀번호를 입력해도 423이 반환됩니다.** 프론트는 423 응답 시 message를 그대로 안내하고, "비밀번호 재설정" (10→11→13번 흐름) 버튼을 함께 보여주세요 — 재설정에 성공하면 잠금이 즉시 해제됩니다.
- `rememberMe: true`면 refreshToken 유효기간이 14일, `false`면 짧게(세션성)
- 구글 로그인은 비밀번호가 없으므로 잠금 대상이 아닙니다.

### 6. 토큰 재발급
`POST /auth/token/refresh`
```json
{ "refreshToken": "..." }
```
응답: `{ "data": { "accessToken": "새토큰", "refreshToken": "새토큰" } }` — refreshToken도 매번 새로 발급됨(재사용 불가, 응답의 새 토큰으로 계속 교체해서 저장해야 함)
- 실패: `INVALID_REFRESH_TOKEN` (401) — accessToken 만료 시 이 API로 갱신, 이마저 실패하면 재로그인 필요

### 7. 로그아웃
`POST /auth/logout`
```json
{ "refreshToken": "..." }
```
- 성공: 200 (이미 무효한 토큰으로 호출해도 에러 없이 200)

---

### 8. 아이디 찾기 — 인증코드 발송
`POST /auth/find-id/send-code`
```json
{ "email": "user@example.com" }
```
- 성공: 200
- 실패: `USER_NOT_FOUND` (404) — 가입되지 않은 이메일이거나, 구글 로그인 계정(아이디 자체가 없는 계정)인 경우
- 2번(회원가입용 이메일 인증코드 발송)과 같은 Redis 키/쿨다운/시도제한을 공유함 (완전히 별개 API는 아니고 "발송 목적"만 다름)

### 9. 아이디 찾기 — 인증코드 확인
`POST /auth/find-id/verify-code`
```json
{ "email": "user@example.com", "code": "123456" }
```
응답: `{ "data": { "loginId": "tester01", "maskedLoginId": "te******", "createdAt": "2026-01-01T12:00:00" } }`
- 성공: 200, 실제 아이디 + 마스킹된 아이디(앞 2글자만 노출) + 가입일 반환
- 실패: `INVALID_VERIFICATION_CODE` (400)
- **화면에는 `maskedLoginId`만 보여주세요.** `loginId`(실제 값)는 화면에 표시하지 말고, "비밀번호 재설정" 버튼을 눌렀을 때 12/13번 API 호출용 파라미터로만 프론트 상태(state)에 숨겨서 들고 있으면 됩니다 — 12/13번 API가 실제 `loginId`를 요구하기 때문에 필요합니다.
- **중요**: 이 API 성공 시 내부적으로 "본인인증 완료" 상태가 되고, 이 상태는 10번(비밀번호 재설정) API에서도 그대로 인정됩니다. 즉 "아이디 찾기 → 바로 비밀번호 재설정" 버튼을 눌러도 이메일 인증을 다시 안 해도 됩니다.

### 10. 비밀번호 찾기 — 인증코드 발송
`POST /auth/password/send-code`
```json
{ "loginId": "tester01", "email": "user@example.com" }
```
- 성공: 200
- 실패: `USER_NOT_FOUND` (404) — 아이디가 없거나, 아이디는 있지만 입력한 이메일이 그 계정의 이메일과 다른 경우 (구분해서 알려주지 않음 — 보안상 의도된 동작)

### 11. 비밀번호 찾기 — 인증코드 확인
`POST /auth/password/verify-code`
```json
{ "loginId": "tester01", "email": "user@example.com", "code": "123456" }
```
- 성공: 200 — 본인인증 완료 상태로 전환 (10분간 유지)
- 실패: `USER_NOT_FOUND` (404, 아이디+이메일 불일치) 또는 `INVALID_VERIFICATION_CODE` (400, 코드 불일치)

### 12. 비밀번호 재설정 — 인증 상태 확인
`GET /auth/password/verification-status?loginId={아이디}`

응답: `{ "data": { "verified": true } }`
- 비밀번호 재설정 화면 진입 시 이 API로 먼저 확인해서, `verified: true`면 (아이디찾기 경유 등으로 이미 인증된 상태면) 인증 단계 없이 바로 새 비밀번호 입력 폼만 보여주면 됩니다.

### 13. 비밀번호 재설정
`POST /auth/password/reset`
```json
{ "loginId": "tester01", "newPassword": "newPassword1!" }
```
- 성공: 200. **재설정 즉시 그 계정의 기존 로그인 세션(발급된 refreshToken)이 전부 무효화됩니다** — 재설정 후에는 반드시 새로 로그인해야 함(자동 로그인 유지 안 됨). 프론트에서 재설정 성공 시 저장해둔 토큰을 지우고 로그인 화면으로 보내주세요.
- 실패:
  - `IDENTITY_NOT_VERIFIED` (403) — 9번 또는 11번 인증을 먼저 안 했으면
  - 비밀번호 형식 불일치 (400)
- 검증 규칙: newPassword **8~16자이며 영문/숫자/특수문자를 모두 포함**, 필수 (회원가입과 동일한 규칙)

### 14. 구글 로그인
`POST /auth/google/login`
```json
{ "code": "구글 인가코드", "redirectUri": "http://localhost:5173/oauth/google/callback" }
```
- 프론트가 Google 로그인 버튼 클릭 시 Google 동의화면으로 직접 리다이렉트(`client_id`, `redirect_uri`, `scope=openid email profile`, `response_type=code`)한 뒤, Google이 그 `redirect_uri`로 돌려준 `code`를 이 API로 전달합니다.
- `redirectUri`는 위 리다이렉트에 실제로 사용한 값과 **정확히 동일**해야 합니다(다르면 구글 쪽에서 거부됩니다).
- 응답: `{ "data": { "accessToken": "...", "refreshToken": "..." } }` — 로그인 API와 동일한 포맷이며, 신규 이메일이면 내부적으로 자동 회원가입됩니다(구글 계정은 `loginId`/`password`가 없습니다 — 아이디/비밀번호 찾기 대상이 아닙니다).
- 구글 로그인은 항상 "로그인상태유지"로 처리되어 refreshToken이 14일짜리로 발급됩니다.
- 실패: `EMAIL_ALREADY_REGISTERED_AS_LOCAL` (409) — 같은 이메일로 이미 일반 회원가입된 계정이 있는 경우(자동 연동하지 않음, 일반 로그인 안내). `GOOGLE_AUTH_FAILED` (502) — code가 만료/재사용되었거나 구글 API 통신에 실패한 경우.
- **동의 처리**: 구글 로그인으로 신규 가입되는 경우 필수 약관(이용약관/개인정보 수집·이용)에 동의한 것으로 처리되고 마케팅 수신은 미동의로 기록됩니다. 프론트는 구글 로그인 버튼 근처에 "구글 로그인 시 이용약관 및 개인정보처리방침에 동의한 것으로 간주됩니다" 문구를 표시해주세요 (문구 안의 각 약관명은 15번 API 본문을 보여주는 링크로 처리 권장).

### 15. 약관 본문 조회
`GET /terms/{type}` — `type`: `TERMS`(서비스 이용약관) 또는 `PRIVACY`(개인정보 수집·이용). 대소문자 무관, 인증 불필요.

응답: `{ "data": { "type": "TERMS", "version": "1.0", "content": "...마크다운 본문..." } }`
- `content`는 마크다운 텍스트 — 프론트에서 마크다운 렌더러로 표시 권장
- 실패: `TERMS_NOT_FOUND` (404) — 없는 타입
- 사용처: 회원가입 동의 항목 "전문 보기" 모달, **서비스 메인 화면 하단(푸터)의 "개인정보처리방침" 링크 페이지** (컴플라이언스 요건 — 푸터 링크 필수)

### 16. 내 동의 현황 조회
`GET /users/me/consents` — `Authorization: Bearer {accessToken}` 필요

응답:
```json
{ "data": { "consents": [
  { "type": "TERMS",     "agreed": true,  "version": "1.0", "agreedAt": "2026-07-16T12:00:00" },
  { "type": "PRIVACY",   "agreed": true,  "version": "1.0", "agreedAt": "2026-07-16T12:00:00" },
  { "type": "MARKETING", "agreed": false, "version": "1.0", "agreedAt": "2026-07-16T12:00:00" }
] } }
```
- 동의 기록이 없는 항목(동의 기능 도입 전 가입자)은 `agreed`/`version`/`agreedAt`이 `null`
- 사용처: 마이페이지 "약관 및 동의 관리" 화면

### 17. 마케팅 수신 동의 변경
`PUT /users/me/consents/marketing` — `Authorization: Bearer {accessToken}` 필요
```json
{ "agreed": true }
```
응답: `{ "data": { "type": "MARKETING", "agreed": true, "version": "1.0", "agreedAt": "..." } }`
- 동의/철회 모두 이 API 하나로 처리 (마이페이지 토글)
- 필수 동의(TERMS/PRIVACY)는 변경 API가 없음 — 철회하려면 회원탈퇴 절차 필요

## 에러 코드 전체 목록

| 상황 | HTTP | message |
|---|---|---|
| 아이디 중복 | 409 | 이미 사용 중인 아이디입니다. |
| 이메일 중복 | 409 | 이미 가입된 이메일입니다. |
| 이메일 인증 안 됨 | 400 | 이메일 인증이 필요합니다. |
| 인증번호 불일치/만료/시도초과 | 400 | 인증번호가 일치하지 않거나 만료되었습니다. |
| 인증코드 재발송 쿨다운 | 429 | 잠시 후 다시 시도해주세요. |
| 로그인 실패 | 401 | 아이디 또는 비밀번호가 일치하지 않습니다. |
| 리프레시 토큰 무효/만료 | 401 | 로그인이 만료되었습니다. 다시 로그인해주세요. |
| 회원 정보 없음 (아이디/비번찾기) | 404 | 일치하는 회원 정보를 찾을 수 없습니다. |
| 본인인증 안 됨 (비밀번호 재설정) | 403 | 본인 인증이 필요합니다. |
| 입력값 검증 실패 (`@Valid`) | 400 | 필드별 메시지가 `data`에 `{필드명: 메시지}` 형태로 들어옴 |
| 이미 일반 회원가입된 이메일로 구글 로그인 시도 | 409 | 이미 일반 회원가입으로 등록된 이메일입니다. 일반 로그인을 이용해주세요. |
| 구글 인증 실패(코드 만료/재사용, 구글 API 오류) | 502 | 구글 인증에 실패했습니다. |
| 약관 타입 없음 | 404 | 약관을 찾을 수 없습니다. |
| 필수 약관 미동의 (회원가입) | 400 | `data`에 `{termsAgreed/privacyAgreed: "필수 약관에 동의해야 합니다."}` |
| 계정 잠금 (로그인 5회 실패) | 423 | 로그인 시도 횟수를 초과하여 계정이 일시적으로 잠겼습니다. N분 후 다시 시도해주세요. |

## 화면 흐름 참고

- **아이디 찾기**: 이메일 입력 → 인증코드 발송(8) → 코드 확인(9) → 마스킹된 아이디 + 가입일 표시 → "로그인하기" 버튼(실제 아이디를 로그인폼에 채워주면 됨) 또는 "비밀번호 재설정" 버튼(12→13 흐름으로 바로 이동, 재인증 불필요)
- **비밀번호 찾기(로그인 화면에서 바로 진입)**: 아이디+새비번+새비번확인+이메일+인증번호를 한 화면에 — 인증(10→11) 전엔 "변경하기" 버튼 비활성화, 인증 후 활성화 → 13번 호출
- **비밀번호 재설정(아이디찾기 경유)**: 아이디는 readonly로 이미 채워짐, 새비번+새비번확인만 입력 → 12번으로 인증 상태 확인해서 폼 분기 → 13번 호출
- 두 진입 경로 모두 최종 제출은 동일한 13번 API

## 아직 없는 것 (다음 Phase 예정)

- 관리자 로그인/권한 관리
- 엔드포인트 인증 강제(현재는 토큰 없이도 모든 API 호출 가능)
