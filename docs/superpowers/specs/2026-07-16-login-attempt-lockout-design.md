# 로그인 시도 제한 및 계정 잠금 (Phase 5) 설계 문서

## 배경

「개인정보의 안전성 확보조치 기준」 제5조 제6항: "계정정보 또는 비밀번호를 일정 횟수 이상 잘못 입력한 경우 접근을 제한하는 등 필요한 기술적 조치" — 현재 로그인 API는 시도 횟수 제한이 전혀 없어 브루트포스 공격에 무방비다. 비밀번호 5회 오입력 시 계정을 일시 잠금하는 기능을 구현한다.

## 범위

**포함:**
- 로그인 실패 5회 시 계정 잠금 (아이디 기준)
- 점진적 잠금 시간: 1번째 5분 → 2번째 15분 → 3번째 이후 30분
- 시간 경과 시 자동 해제 (Redis TTL)
- 로그인 성공/비밀번호 재설정 시 카운터·잠금 초기화
- 잠금 에러 메시지에 남은 시간(분) 포함

**제외 (후속 과제):**
- IP 기준 차단 (컴플라이언스 요구는 계정 기준)
- 관리자에 의한 수동 잠금/해제
- 잠금 발생 시 이메일 알림

## 핵심 결정사항

| 결정 | 선택 | 근거 |
|---|---|---|
| 저장소 | Redis (카운터 + TTL) | 기존 `EmailVerificationService` 패턴과 동일. 자동 해제가 TTL로 공짜. `User` 엔티티 변경 없음 |
| 해제 방식 | 시간 경과 자동 해제 | 별도 해제 화면/API 불필요. 비밀번호 재설정이 사실상의 즉시 해제 경로로 이미 존재 |
| 잠금 시간 | 고정 3단계 5분→15분→30분 (4번째부터 30분 유지) | 반복 공격에 점진적으로 강한 벽, 계산·테스트 단순 |
| 임계치 | 5회 실패 | 컴플라이언스 문서 예시("5회 이상") 그대로 |
| 에러 응답 | 423 Locked + 남은 시간 포함 동적 메시지 | 401(인증실패)/429(요청과다)와 구분되는 전용 코드. 프론트가 잠금 안내 화면 분기 가능 |
| 동적 메시지 | `CustomException(ErrorCode, String customMessage)` 생성자 추가 | 기존 클래스 확장만. `GlobalExceptionHandler`는 `e.getMessage()`를 쓰므로 변경 불필요 |

## Redis 키 설계

키 프리픽스와 상수는 신규 `LoginAttemptService`에 정의한다 (`application.yaml` 설정화는 YAGNI).

| 키 | 값 | TTL | 용도 |
|---|---|---|---|
| `login-fail:{loginId}` | 실패 횟수 (increment) | 30분 (매 실패마다 갱신) | 5회 도달 시 잠금 트리거 |
| `login-lock-level:{loginId}` | 잠금 단계 1~3 | 24시간 (잠금 발생 시마다 갱신) | 연속 잠금 시 단계 상승 |
| `login-locked:{loginId}` | `"true"` | 단계별 5/15/30분 | 잠금 상태. 존재 여부로 차단 판정, TTL 만료 = 자동 해제 |

상수: `MAX_ATTEMPTS = 5`, 잠금 시간 `{5, 15, 30}`분, 실패 카운터 윈도우 30분, 단계 유지 24시간.

## 동작 흐름

1. **로그인 시도** → `login-locked:{loginId}` 존재 시 즉시 `ACCOUNT_LOCKED`(423) — 비밀번호 검증 자체를 하지 않는다 (올바른 비밀번호여도 잠금 중엔 차단).
2. **비밀번호 불일치** → `login-fail` increment + TTL 30분 재설정. 5회 도달 시:
   - `login-lock-level` 1 증가 (최대 3), TTL 24시간 재설정
   - 해당 단계 시간(5/15/30분)으로 `login-locked` 설정
   - `login-fail` 삭제
   - 이번 요청은 `ACCOUNT_LOCKED`(423)로 응답 (남은 시간 = 방금 설정한 잠금 시간)
3. **로그인 성공** → `login-fail`, `login-lock-level` 삭제 (다음 잠금은 다시 1단계 5분부터).
4. **비밀번호 재설정 성공** (`UserService.resetPassword`) → 3개 키 전부 삭제. 이메일 인증을 거친 본인이므로 즉시 해제 — 잠긴 사용자의 사실상 즉시 해제 경로.
5. **존재하지 않는 아이디** → 카운트하지 않는다. `findByLoginId` 실패 시 기존처럼 `INVALID_CREDENTIALS`(401)로 끝. Redis에 흔적을 남기지 않는다.

단, 잠금 확인(1번)은 `findByLoginId` 이전에 수행한다 — 잠금 키는 존재하는 계정에 대해서만 만들어지므로 순서상 문제 없음.

## 컴포넌트 설계

### LoginAttemptService (신규, `user.service`)

`StringRedisTemplate`만 의존하는 독립 서비스.

```java
void checkNotLocked(String loginId);   // 잠금 중이면 CustomException(ACCOUNT_LOCKED, 남은시간 포함 메시지)
void recordFailure(String loginId);    // 실패 기록. 5회 도달 시 잠금 설정 후 동일 예외 throw
void recordSuccess(String loginId);    // login-fail, login-lock-level 삭제
void clearLockState(String loginId);   // 3개 키 전부 삭제 (비밀번호 재설정용)
```

- 남은 시간: `redisTemplate.getExpire(lockedKey)` (초) → 분으로 올림, 최소 1분 표기.
- 동적 메시지 예: `"로그인 시도 횟수를 초과하여 계정이 일시적으로 잠겼습니다. 12분 후 다시 시도해주세요."`

### AuthService.login() 변경

```java
public TokenResponse login(LoginRequest request) {
    loginAttemptService.checkNotLocked(request.getLoginId());   // 추가

    User user = userRepository.findByLoginId(...)               // 기존
            .orElseThrow(() -> new CustomException(INVALID_CREDENTIALS));

    if (!passwordEncoder.matches(...)) {
        loginAttemptService.recordFailure(request.getLoginId()); // 추가 (5회 도달 시 여기서 423 throw)
        throw new CustomException(INVALID_CREDENTIALS);
    }

    loginAttemptService.recordSuccess(request.getLoginId());     // 추가
    ...기존 토큰 발급...
}
```

생성자에 `LoginAttemptService` 파라미터 추가 (7번째). 구글 로그인(`googleLogin`)은 비밀번호 검증이 없으므로 변경 없음.

### UserService.resetPassword() 변경

기존 `refreshTokenRepository.deleteByUser(user)` 다음에 `loginAttemptService.clearLockState(loginId)` 한 줄 추가. 생성자에 `LoginAttemptService` 파라미터 추가.

### CustomException 확장 (`global.exception`)

```java
public CustomException(ErrorCode errorCode, String customMessage) {
    super(customMessage);
    this.errorCode = errorCode;
}
```

기존 생성자 유지. `GlobalExceptionHandler` 변경 없음 (`e.getMessage()` + `e.getErrorCode().getStatus()` 그대로 동작).

### ErrorCode 추가

```java
ACCOUNT_LOCKED(HttpStatus.LOCKED, "로그인 시도 횟수를 초과하여 계정이 일시적으로 잠겼습니다.")
```

(기본 메시지는 폴백용. 실제 응답은 남은 시간이 붙은 동적 메시지.)

## API 변경

신규 엔드포인트 없음. `POST /auth/login` 실패 케이스 추가:

```json
{ "success": false, "message": "로그인 시도 횟수를 초과하여 계정이 일시적으로 잠겼습니다. 12분 후 다시 시도해주세요.", "data": null }
```
(HTTP 423)

- 5회 미만 실패는 기존 401 `INVALID_CREDENTIALS` 그대로 — 남은 시도 횟수는 알려주지 않는다 (공격자에게 임계치 정보 제공 방지).

## 명시적 경계 사항

- **구글 로그인 무관**: 비밀번호 검증이 없으므로 잠금 대상 아님.
- **기존 세션 유지**: 잠금은 신규 로그인 차단만. 이미 발급된 refreshToken은 유효 (브루트포스 방어 목적이지 계정탈취 대응이 아님 — 의도된 동작).
- **계정 존재 노출 트레이드오프 수용**: 5회 실패 후 423 응답이 뜨는 순간 해당 아이디의 존재가 노출된다. 남은 시간 안내 UX를 선택한 데 따른 수용된 트레이드오프.
- **Redis 장애 시**: 로그인이 500으로 실패한다. 이메일 인증과 동일한 기존 수용 리스크 (Redis는 이미 필수 인프라).
- **동시성**: Redis increment는 원자적. 동시 실패 요청으로 카운터가 5를 건너뛰어도 `>= MAX_ATTEMPTS` 비교로 처리됨.

## 테스트

기존 컨벤션(Mockito 단위 테스트 + `@WebMvcTest`) 그대로.

- `LoginAttemptServiceTest` (신규): 잠금 없으면 통과, 잠금 중이면 남은 시간 포함 423 예외, 5회 미만 실패는 카운트만, 5회 도달 시 1단계 5분 잠금 + 예외, 2·3번째 잠금 15/30분, 4번째도 30분 유지, `recordSuccess`/`clearLockState`의 키 삭제
- `AuthServiceTest` (수정): 생성자 파라미터 추가(기존 테스트 전부), 잠금 상태면 `findByLoginId` 호출 전 예외, 비밀번호 불일치 시 `recordFailure` 호출, 성공 시 `recordSuccess` 호출
- `UserServiceTest` (수정): 생성자 파라미터 추가, `resetPassword` 성공 시 `clearLockState` 호출
- `AuthApiControllerTest` (수정): 로그인 423 응답 케이스 1개 추가
- `CustomException` 신규 생성자: 별도 테스트 없이 위 테스트들이 메시지 검증으로 커버

## 문서

`docs/API_REFERENCE.md` 갱신:
- 5번(로그인)에 423 잠금 케이스 + "잠금 안내 화면에서 비밀번호 재설정(잠금 즉시 해제됨) 유도" 가이드
- 에러 코드 표에 1행 추가
