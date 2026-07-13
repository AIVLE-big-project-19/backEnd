# 로그인/회원가입 기능 구현 스펙 (개정판)

원본: `AUTH_SPEC.md` (사용자 제공) 검토 후 개정. 개정 사유는 각 절의 **변경 이유** 참고.

## 0. 개정 배경

- 프론트엔드와 백엔드는 **cross-origin 분리 배포**가 실제 운영 방식 (현재 `CorsConfig`가 `localhost:5173`을 허용 중이며, 커스텀 도메인/서브도메인 통일 여부는 미정).
- 도메인 구조가 확정되지 않은 상태에서도 안전하게 동작해야 하므로, 세션 쿠키 기반 인증 대신 **JWT 기반 인증**으로 전환.
- 기존 프로젝트(board/comment 도메인)에 이미 확립된 응답/예외 컨벤션이 있어, 이를 재사용.

## 1. 기술 스택 / 아키텍처

- Spring Boot (기존 프로젝트 버전 유지)
- Spring Security 6 (JWT 인증 필터 + OAuth2 Client) — 세션/Remember-Me 미사용
- Spring Data JPA + MySQL (기존과 동일)
- Redis (이메일 인증코드/인증플래그 저장 전용, 세션 저장소 아님)
- spring-boot-starter-mail (이메일 인증번호 발송)

**배포 방식: 프론트/백엔드 분리 배포.** 프론트는 별도 정적 호스팅(Vercel/Netlify/S3 등), 백엔드는 별도 서버. 서로 다른 origin이므로 **CORS 화이트리스트 설정이 필수**이고, 인증은 쿠키가 아닌 **JWT를 응답 body로 주고받는 방식**으로 도메인 구조에 의존하지 않게 한다.

```
[프론트엔드 (별도 origin)]
    │  fetch(API) + Authorization: Bearer {accessToken}
    ▼
[백엔드 Spring Boot 서버] (context-path: /api, 기존 설정 유지)
  ├─ Spring Security (JWT 인증 필터 + OAuth2 Client)
  ├─ Controller(API) → Service → Repository
  └─ DB(MySQL) + Redis(이메일 인증 전용)
```

> **변경 이유**: 세션 쿠키 방식은 프론트/백엔드가 다른 도메인일 때 브라우저의 서드파티 쿠키 차단 정책(Safari ITP 등)에 막힐 수 있고, 이는 커스텀 도메인/서브도메인 구조가 확정되어야 안전하게 회피 가능하다. 도메인 구조가 미정인 현재로서는 JWT(Authorization 헤더 전송)가 도메인 구조와 무관하게 항상 동작하는 유일한 방식이라 이쪽으로 확정.

## 2. 패키지 구조

기존 프로젝트 컨벤션(`global`)을 따른다. 스펙 원안의 `common` 대신 `global` 사용.

```
com.example.demo
 ├─ user
 │   ├─ controller
 │   │   └─ AuthApiController
 │   ├─ service
 │   │   ├─ UserService              (회원가입, 아이디중복확인, 비번변경, 아이디/비번찾기)
 │   │   ├─ EmailVerificationService  (인증번호 발송/검증, Redis)
 │   │   ├─ TokenService              (Access/Refresh 토큰 발급·검증·재발급)
 │   │   └─ CustomOAuth2UserService   (구글 로그인 upsert)
 │   ├─ repository
 │   │   ├─ UserRepository
 │   │   └─ RefreshTokenRepository
 │   ├─ entity
 │   │   ├─ User
 │   │   └─ RefreshToken
 │   └─ dto
 │       └─ SignupRequest, LoginRequest, TokenResponse, EmailCodeRequest, FindIdResponse, ResetPasswordRequest 등
 ├─ admin
 │   ├─ controller / AdminController
 │   ├─ service / AdminService
 │   └─ dto / RoleUpdateRequest
 └─ global                            (기존 프로젝트 컨벤션, "common" 아님)
     ├─ exception
     │   ├─ ErrorCode                 (기존 enum에 auth 관련 코드 추가)
     │   ├─ CustomException           (기존 클래스 재사용, 신규 예외 클래스 만들지 않음)
     │   └─ GlobalExceptionHandler    (기존 핸들러 재사용/확장)
     ├─ response
     │   ├─ ApiResponse               (기존 재사용)
     │   └─ SuccessCode               (기존 enum에 USER_* 코드 이미 존재, 추가분만 보강)
     └─ config
         ├─ SecurityConfig           (기존 파일 확장: JWT 필터체인 + 관리자 필터체인 분리)
         ├─ CorsConfig               (기존 파일 확장: 배포 도메인 화이트리스트)
         ├─ RedisConfig
         └─ MailConfig
```

> **변경 이유(패키지/컨벤션)**: `board`/`comment` 도메인이 이미 `ApiResponse<T>` + `SuccessCode`(`USER_LOGIN`, `USER_SIGNUP` 이미 정의됨) + `ErrorCode` + `CustomException` + `GlobalExceptionHandler` 패턴을 쓰고 있음. AUTH_SPEC 원안의 `{message: string}` 단독 포맷, 도메인별 개별 예외 클래스(`DuplicateLoginIdException` 등) 신설안은 폐기하고 기존 패턴에 맞춘다.

## 3. DB 스키마

### users (원안과 동일)

| 컬럼 | 타입 | 설명 |
|---|---|---|
| id | BIGINT PK | |
| login_id | VARCHAR, UNIQUE, nullable | 일반가입자만 값 존재 |
| email | VARCHAR, UNIQUE | 소셜/일반 공통, unique 제약 |
| password | VARCHAR, nullable | 소셜 전용 계정은 null |
| name | VARCHAR | |
| provider | ENUM(LOCAL, GOOGLE) | |
| provider_id | VARCHAR, nullable | 구글 로그인 시 구글 고유 ID(sub) |
| role | ENUM(USER, ADMIN) | |
| created_at / updated_at | DATETIME | |

### refresh_tokens (신규, `persistent_logins` 대체)

| 컬럼 | 타입 | 설명 |
|---|---|---|
| id | BIGINT PK | |
| user_id | BIGINT FK → users.id | |
| token_hash | VARCHAR, UNIQUE | Refresh Token은 해시로 저장(탈취 시 원문 노출 방지) |
| expires_at | DATETIME | "로그인상태유지" 체크 시 14일, 미체크 시 짧게(예: 1일) |
| created_at | DATETIME | |

> **변경 이유**: Remember-Me 표준 스키마(`persistent_logins`, `JdbcTokenRepositoryImpl`)는 세션 기반 폼 로그인 전용 메커니즘이라 JWT 전환 시 그대로 쓸 수 없음. Refresh Token을 자체 테이블로 관리하는 방식으로 대체.

## 4. Redis 키 설계

| 키 패턴 | 값 | TTL | 용도 |
|---|---|---|---|
| `email-code:{email}` | 6자리 코드 | 5분 | 인증번호 발송/검증 |
| `email-verified:{email}` | true | 30분 | 회원가입 시 이메일 인증 완료 여부 |
| `identity-verified:{loginId}` | true | 10분 | 아이디/비밀번호 찾기 인증 완료 여부 |
| `access-token-blacklist:{jti}` | true | 토큰 잔여 만료시간 | 로그아웃 시 즉시 무효화용 (선택) |

> **변경 이유**: `spring:session:*` 키 제거 — 세션 저장소 용도가 사라졌으므로 Redis는 원래 목적(이메일 인증)에만 집중. 로그아웃 즉시 반영이 필요하면 access token 블랙리스트를 선택적으로 추가.

인증번호 재발송 남용 방지 규칙은 원안과 동일하게 유지 (1분 쿨다운).

## 5. 페이지 구성

원안과 동일 (React 멀티 페이지, 프론트 별도 저장소). 단, 프론트/백엔드가 분리 배포이므로 "Spring Boot가 같은 도메인에서 서빙"하는 부분은 적용하지 않고, 프론트는 자체 호스팅 + API만 백엔드로 fetch.

`/reset-password.html?loginId=`의 상태 확인 API(`GET /api/auth/password/verification-status`)는 원안과 동일하게 유지.

## 6. REST API

| 구분 | Method | URI | Request | Response | 설명 |
|---|---|---|---|---|---|
| 회원가입 | GET | /api/auth/check-login-id?value= | - | `{available}` | 아이디 중복확인 |
| 회원가입 | POST | /api/auth/email/send-code | `{email}` | 200 | 인증번호 발송 |
| 회원가입 | POST | /api/auth/email/verify-code | `{email, code}` | `{verified}` | 인증번호 검증 |
| 회원가입 | POST | /api/auth/signup | `{loginId, email, password}` | 201 | 최종 가입 |
| **로그인** | **POST** | **/api/auth/login** | `{loginId, password, rememberMe}` | `{accessToken, refreshToken}` | Spring Security 폼 로그인 대신 자체 REST 엔드포인트. 성공 시 Access+Refresh 발급 |
| **토큰 재발급** | **POST** | **/api/auth/token/refresh** | `{refreshToken}` | `{accessToken, refreshToken}` | Refresh Token 검증 후 재발급(회전) |
| **로그아웃** | **POST** | **/api/auth/logout** | `{refreshToken}` | 200 | Refresh Token 폐기 + (선택) access token 블랙리스트 등록 |
| 아이디 찾기 | POST | /api/auth/find-id/send-code | `{email}` | 200 | |
| 아이디 찾기 | POST | /api/auth/find-id/verify-code | `{email, code}` | `{maskedLoginId, createdAt}` | |
| 비밀번호 찾기 | POST | /api/auth/password/send-code | `{loginId, email}` | 200 | |
| 비밀번호 찾기 | POST | /api/auth/password/verify-code | `{loginId, email, code}` | 200 | |
| 비밀번호 재설정 | GET | /api/auth/password/verification-status?loginId= | - | `{verified}` | |
| 비밀번호 재설정 | POST | /api/auth/password/reset | `{loginId, newPassword}` | 200 | |
| **구글 로그인 콜백 교환** | **POST** | **/api/auth/oauth2/token** | `{exchangeCode}` | `{accessToken, refreshToken}` | OAuth2 콜백에서 받은 1회용 코드를 토큰으로 교환 (URL에 토큰 직접 노출 방지) |
| 관리자 | PATCH | /api/admin/users/{id}/role | `{role}` | 200 | ADMIN 전용 |

Spring Security/OAuth2 Client가 처리(컨트롤러 불필요, `context-path: /api` 자동 적용):
- `GET /api/oauth2/authorization/google` — 구글 로그인 시작
- `GET /api/login/oauth2/code/google` — 콜백. `CustomOAuth2UserService`에서 upsert 후, 프론트 콜백 페이지로 1회용 `exchangeCode`를 쿼리파라미터로 리다이렉트 (토큰 자체는 URL에 노출하지 않음) → 프론트가 `POST /api/auth/oauth2/token`으로 실제 토큰 교환

> **변경 이유**: 원안의 `POST /login`(Spring Security 폼 로그인), `POST /logout`은 세션 기반 인증 전제라 JWT 전환 시 그대로 못 씀. 자체 REST 엔드포인트로 대체하고, 토큰 재발급/로그아웃 API를 신설. 구글 OAuth2는 Authorization Code Flow 자체는 Spring Security가 처리하되, 콜백 이후 토큰 전달은 1회용 코드 교환 스텝을 추가해 URL에 JWT가 그대로 노출되는 걸 방지. Google Cloud Console에 등록하는 redirect URI는 `context-path: /api`가 반영된 `/api/login/oauth2/code/google` 이어야 함.

## 7. Security 설정 요점

- 필터체인 2개 분리: 일반 유저(JWT 인증 필터) / 관리자(`/admin/**`, `hasRole('ADMIN')`)
- 관리자 계정은 회원가입 없음. DB 시딩(`data.sql` 또는 `ApplicationRunner`)으로 최초 1회 생성, 비밀번호는 반드시 BCrypt 인코딩 상태로 저장
- 추가 관리자는 기존 관리자가 `/api/admin/users/{id}/role`로 승격
- CORS: 배포 도메인 화이트리스트 (`CorsConfiguration.setAllowedOrigins`에 프론트 실제 배포 도메인 등록, 로컬 개발용 `localhost:5173`은 profile별로 분리 관리 권장)

## 8. 소셜 로그인(구글) 계정 연동 정책

원안에 없던 부분. 이메일 unique 제약과 LOCAL/GOOGLE provider 구분이 충돌하는 케이스에 대한 정책을 명시한다.

- 같은 이메일로 **LOCAL 계정이 이미 존재**하는 상태에서 구글 로그인을 시도하면: 자동 연동하지 않고 **거부**, `"이미 가입된 이메일입니다. 아이디/비밀번호로 로그인해주세요."` 안내
- 처음 보는 이메일이면 정상적으로 GOOGLE provider 계정 신규 생성(upsert)

> **변경 이유**: 자동 연동은 이메일 검증 여부와 무관하게 계정 탈취 경로가 될 수 있어(예: 공격자가 피해자 이메일로 먼저 구글 계정을 만들어 연동 유도) 명시적으로 차단.

## 9. Board/Comment의 writer ↔ User 연동

**이번 로그인 기능 구현 범위에는 포함하지 않음.** 다만 아래 사실을 기록해 추후 작업으로 명시한다.

- 현재 `BoardRequest.writer`, `Comment.writer`는 클라이언트가 자유롭게 보내는 문자열이며 인증과 무관하게 검증 없이 저장됨 (`@NotBlank`만 체크)
- 로그인 기능이 도입된 이후에는 writer를 클라이언트 입력이 아닌 **인증된 사용자(SecurityContext)** 기준으로 서버가 채우고, 수정/삭제 시 작성자 본인 확인 로직이 필요함
- 이 작업은 별도 스펙/작업으로 분리 진행

## 10. 예외 처리

기존 `global.exception.ErrorCode` enum에 아래 항목을 추가하고, `CustomException(errorCode)`를 던지는 기존 패턴을 그대로 사용한다. 신규 예외 클래스나 신규 핸들러를 만들지 않는다.

| ErrorCode (추가분) | 상황 | HTTP |
|---|---|---|
| DUPLICATE_LOGIN_ID | 이미 사용 중인 아이디 | 409 |
| DUPLICATE_EMAIL | 이미 가입된 이메일 | 409 |
| INVALID_VERIFICATION_CODE | 인증번호 불일치/만료 | 400 |
| EMAIL_VERIFICATION_REQUIRED | 이메일 인증 없이 가입 제출 | 400 |
| USER_NOT_FOUND | 아이디/비번 찾기에서 일치 회원 없음 | 404 |
| IDENTITY_NOT_VERIFIED | 인증 플래그 없이 비밀번호 재설정 시도 | 403 |
| INVALID_REFRESH_TOKEN | Refresh Token 만료/위조/폐기됨 | 401 |
| OAUTH2_EMAIL_ALREADY_REGISTERED | LOCAL로 이미 가입된 이메일로 구글 로그인 시도 | 409 |

`MethodArgumentNotValidException`, 기타 예외는 기존 `GlobalExceptionHandler`가 이미 처리하는 방식 그대로 재사용.

## 11. 화면 흐름 요약

원안과 동일 (회원가입/로그인/아이디찾기/비밀번호찾기 흐름 자체는 변경 없음). 차이는 "로그인상태유지" 체크가 Remember-Me 쿠키가 아니라 **Refresh Token 만료기간**으로 구현된다는 점뿐.

## 12. 이메일 발송

원안과 동일 (Gmail SMTP 개발, 운영 시 교체, `@Async` 비동기 발송).

## 13. 개발 진행 순서

1. 프로젝트 셋업 (의존성: Security, Data JPA, Redis, Validation, OAuth2 Client, Mail, JWT 라이브러리 / 프론트: Vite 멀티 페이지)
2. DB/Entity — User, RefreshToken 엔티티, Repository
3. Security 기본 설정 — JWT 인증 필터, 비밀번호 인코더
4. 회원가입/로그인 페이지(React) + API 컨트롤러 + JWT 발급/재발급/로그아웃 (핵심 마일스톤)
5. 이메일 인증 (Redis + JavaMailSender)
6. 아이디/비밀번호 찾기
7. 구글 OAuth2 연동 + 계정 연동 정책 적용
8. 관리자 필터체인 + 초기 관리자 시딩 + role 변경 API
9. 예외처리/유효성 검증 정리 + 테스트
10. (별도 작업) Board/Comment writer ↔ User 연동
