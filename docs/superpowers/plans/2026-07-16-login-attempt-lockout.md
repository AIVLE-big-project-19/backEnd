# 로그인 시도 제한 및 계정 잠금 (Phase 5) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 로그인 실패 5회 시 계정을 점진적(5→15→30분)으로 잠그고 시간 경과 시 자동 해제하며, 잠금 응답(423)에 남은 시간을 포함한다.

**Architecture:** 신규 `LoginAttemptService`가 Redis 카운터+TTL(기존 `EmailVerificationService`와 동일 패턴)로 실패 횟수·잠금 단계·잠금 상태 3개 키를 관리한다. `AuthService.login()`은 잠금 확인/실패 기록/성공 초기화 3줄만 추가하고, `UserService.resetPassword()`는 잠금 완전 초기화 1줄을 추가한다(비밀번호 재설정이 사실상의 즉시 해제 경로). 동적 에러 메시지는 `CustomException`에 커스텀 메시지 생성자를 추가해 처리한다. 상세 설계: `docs/superpowers/specs/2026-07-16-login-attempt-lockout-design.md`.

**Tech Stack:** Spring Boot 4.1.0(기존 유지), Spring Data Redis(`StringRedisTemplate`), JUnit 5 + Mockito(단위 테스트), MockMvc(`@WebMvcTest`).

## Global Constraints

- 신규 예외 클래스를 만들지 않는다. `CustomException`에 `(ErrorCode, String customMessage)` 생성자만 추가하고, `GlobalExceptionHandler`는 변경하지 않는다 (`e.getMessage()`를 이미 사용하므로 그대로 동작).
- 잠금 정책 상수: 임계치 **5회**, 잠금 시간 **{5, 15, 30}분** (4번째 이후도 30분), 실패 카운터 윈도우 **30분**, 잠금 단계 유지 **24시간**. `LoginAttemptService` 안에 상수로 정의 (`application.yaml` 설정화는 YAGNI).
- Redis 키: `login-fail:{loginId}`, `login-lock-level:{loginId}`, `login-locked:{loginId}` — 기존 `email-code:*` 키들과 같은 `프리픽스+식별자` 컨벤션.
- **존재하지 않는 아이디는 카운트하지 않는다** — 잠금 확인은 `findByLoginId` 이전, 실패 기록은 비밀번호 불일치 시에만.
- 5회 미만 실패는 기존 401 `INVALID_CREDENTIALS` 그대로 — 남은 시도 횟수를 응답에 노출하지 않는다.
- 구글 로그인(`googleLogin`)은 비밀번호 검증이 없으므로 변경하지 않는다.
- **이 프로젝트는 Spring Boot 4.1.0 / Spring Framework 7 / Jackson 3 기준이다.** `@WebMvcTest`/`@AutoConfigureMockMvc`는 `org.springframework.boot.webmvc.test.autoconfigure.*` 패키지, `@MockBean` 대신 `org.springframework.test.context.bean.override.mockito.MockitoBean`, `ObjectMapper`는 `tools.jackson.databind.ObjectMapper`. Spring Boot 3.x 예제의 import 경로를 베끼지 말 것.

---

## Task 1: CustomException 생성자 + ErrorCode.ACCOUNT_LOCKED

**Files:**
- Modify: `src/main/java/com/example/demo/global/exception/CustomException.java`
- Modify: `src/main/java/com/example/demo/global/exception/ErrorCode.java`

**Interfaces:**
- Produces: `CustomException(ErrorCode errorCode, String customMessage)` 생성자, `ErrorCode.ACCOUNT_LOCKED` (HttpStatus.LOCKED=423). 이후 모든 Task가 그대로 참조한다.

- [ ] **Step 1: CustomException에 커스텀 메시지 생성자 추가**

`CustomException.java` 전체를 아래로 교체:

```java
package com.example.demo.global.exception;

import lombok.Getter;

@Getter
public class CustomException extends RuntimeException{

    private final ErrorCode errorCode;

    public CustomException(ErrorCode errorCode){

        super(errorCode.getMessage());

        this.errorCode = errorCode;

    }

    public CustomException(ErrorCode errorCode, String customMessage){

        super(customMessage);

        this.errorCode = errorCode;

    }

}
```

- [ ] **Step 2: ErrorCode에 ACCOUNT_LOCKED 추가**

`ErrorCode.java`의 마지막 상수 `TERMS_NOT_FOUND(...)` 뒤 세미콜론을 콤마로 바꾸고 추가:

```java
    TERMS_NOT_FOUND(HttpStatus.NOT_FOUND, "약관을 찾을 수 없습니다."),
    ACCOUNT_LOCKED(HttpStatus.LOCKED, "로그인 시도 횟수를 초과하여 계정이 일시적으로 잠겼습니다.");
```

- [ ] **Step 3: 빌드 확인**

Run: `./gradlew compileJava`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/example/demo/global/exception/CustomException.java src/main/java/com/example/demo/global/exception/ErrorCode.java
git commit -m "feat: 계정 잠금 에러코드 및 CustomException 동적 메시지 생성자 추가"
```

---

## Task 2: LoginAttemptService (Redis 기반 시도 제한/잠금)

**Files:**
- Create: `src/main/java/com/example/demo/user/service/LoginAttemptService.java`
- Test: `src/test/java/com/example/demo/user/service/LoginAttemptServiceTest.java`

**Interfaces:**
- Consumes: `CustomException(ErrorCode, String)` / `ErrorCode.ACCOUNT_LOCKED` (Task 1).
- Produces: `LoginAttemptService.checkNotLocked(String loginId): void`, `recordFailure(String loginId): void`(5회 도달 시 잠금 설정 후 throw), `recordSuccess(String loginId): void`, `clearLockState(String loginId): void`. Task 3/4가 그대로 호출한다.

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/example/demo/user/service/LoginAttemptServiceTest.java`:

```java
package com.example.demo.user.service;

import com.example.demo.global.exception.CustomException;
import com.example.demo.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class LoginAttemptServiceTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private LoginAttemptService loginAttemptService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        loginAttemptService = new LoginAttemptService(redisTemplate);
    }

    @Test
    void 잠금_상태가_아니면_checkNotLocked는_통과한다() {
        when(redisTemplate.hasKey("login-locked:tester01")).thenReturn(false);

        assertThatCode(() -> loginAttemptService.checkNotLocked("tester01"))
                .doesNotThrowAnyException();
    }

    @Test
    void 잠금_상태면_남은_시간을_포함한_예외가_발생한다() {
        when(redisTemplate.hasKey("login-locked:tester01")).thenReturn(true);
        when(redisTemplate.getExpire("login-locked:tester01")).thenReturn(700L);

        assertThatThrownBy(() -> loginAttemptService.checkNotLocked("tester01"))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("12분")
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.ACCOUNT_LOCKED);
    }

    @Test
    void 남은_시간이_1분_미만이어도_1분으로_표기한다() {
        when(redisTemplate.hasKey("login-locked:tester01")).thenReturn(true);
        when(redisTemplate.getExpire("login-locked:tester01")).thenReturn(30L);

        assertThatThrownBy(() -> loginAttemptService.checkNotLocked("tester01"))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("1분");
    }

    @Test
    void 실패가_5회_미만이면_예외없이_카운트만_한다() {
        when(valueOperations.increment("login-fail:tester01")).thenReturn(3L);

        assertThatCode(() -> loginAttemptService.recordFailure("tester01"))
                .doesNotThrowAnyException();

        verify(redisTemplate).expire("login-fail:tester01", 30, TimeUnit.MINUTES);
        verify(valueOperations, never()).set(anyString(), anyString(), anyLong(), any(TimeUnit.class));
    }

    @Test
    void 다섯번째_실패하면_1단계_5분_잠금이_설정되고_예외가_발생한다() {
        when(valueOperations.increment("login-fail:tester01")).thenReturn(5L);
        when(valueOperations.increment("login-lock-level:tester01")).thenReturn(1L);

        assertThatThrownBy(() -> loginAttemptService.recordFailure("tester01"))
                .isInstanceOf(CustomException.class)
                .hasMessageContaining("5분")
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.ACCOUNT_LOCKED);

        verify(valueOperations).set("login-locked:tester01", "true", 5L, TimeUnit.MINUTES);
        verify(redisTemplate).expire("login-lock-level:tester01", 24, TimeUnit.HOURS);
        verify(redisTemplate).delete("login-fail:tester01");
    }

    @Test
    void 두번째_잠금은_15분이다() {
        when(valueOperations.increment("login-fail:tester01")).thenReturn(5L);
        when(valueOperations.increment("login-lock-level:tester01")).thenReturn(2L);

        assertThatThrownBy(() -> loginAttemptService.recordFailure("tester01"))
                .hasMessageContaining("15분");

        verify(valueOperations).set("login-locked:tester01", "true", 15L, TimeUnit.MINUTES);
    }

    @Test
    void 세번째_잠금은_30분이다() {
        when(valueOperations.increment("login-fail:tester01")).thenReturn(5L);
        when(valueOperations.increment("login-lock-level:tester01")).thenReturn(3L);

        assertThatThrownBy(() -> loginAttemptService.recordFailure("tester01"))
                .hasMessageContaining("30분");

        verify(valueOperations).set("login-locked:tester01", "true", 30L, TimeUnit.MINUTES);
    }

    @Test
    void 네번째_이후_잠금도_30분을_유지한다() {
        when(valueOperations.increment("login-fail:tester01")).thenReturn(5L);
        when(valueOperations.increment("login-lock-level:tester01")).thenReturn(4L);

        assertThatThrownBy(() -> loginAttemptService.recordFailure("tester01"))
                .hasMessageContaining("30분");

        verify(valueOperations).set("login-locked:tester01", "true", 30L, TimeUnit.MINUTES);
    }

    @Test
    void recordSuccess는_실패_카운터와_잠금_단계를_삭제한다() {
        loginAttemptService.recordSuccess("tester01");

        verify(redisTemplate).delete("login-fail:tester01");
        verify(redisTemplate).delete("login-lock-level:tester01");
        verify(redisTemplate, never()).delete("login-locked:tester01");
    }

    @Test
    void clearLockState는_모든_키를_삭제한다() {
        loginAttemptService.clearLockState("tester01");

        verify(redisTemplate).delete("login-fail:tester01");
        verify(redisTemplate).delete("login-lock-level:tester01");
        verify(redisTemplate).delete("login-locked:tester01");
    }
}
```

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests "com.example.demo.user.service.LoginAttemptServiceTest"`
Expected: FAIL (컴파일 에러 — `LoginAttemptService` 클래스가 아직 없음)

- [ ] **Step 3: LoginAttemptService 구현**

`src/main/java/com/example/demo/user/service/LoginAttemptService.java`:

```java
package com.example.demo.user.service;

import com.example.demo.global.exception.CustomException;
import com.example.demo.global.exception.ErrorCode;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
public class LoginAttemptService {

    private static final String FAIL_KEY_PREFIX = "login-fail:";
    private static final String LOCK_LEVEL_KEY_PREFIX = "login-lock-level:";
    private static final String LOCKED_KEY_PREFIX = "login-locked:";
    private static final int MAX_ATTEMPTS = 5;
    private static final long[] LOCK_MINUTES = {5, 15, 30};
    private static final long FAIL_WINDOW_MINUTES = 30;
    private static final long LOCK_LEVEL_RETENTION_HOURS = 24;

    private final StringRedisTemplate redisTemplate;

    public LoginAttemptService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void checkNotLocked(String loginId) {
        String lockedKey = LOCKED_KEY_PREFIX + loginId;

        if (Boolean.TRUE.equals(redisTemplate.hasKey(lockedKey))) {
            Long remainingSeconds = redisTemplate.getExpire(lockedKey);
            long remainingMinutes = (remainingSeconds == null || remainingSeconds <= 0)
                    ? 1
                    : (remainingSeconds + 59) / 60;
            throw lockedException(remainingMinutes);
        }
    }

    public void recordFailure(String loginId) {
        String failKey = FAIL_KEY_PREFIX + loginId;

        Long attempts = redisTemplate.opsForValue().increment(failKey);
        redisTemplate.expire(failKey, FAIL_WINDOW_MINUTES, TimeUnit.MINUTES);

        if (attempts != null && attempts >= MAX_ATTEMPTS) {
            long lockMinutes = lockAndGetMinutes(loginId);
            redisTemplate.delete(failKey);
            throw lockedException(lockMinutes);
        }
    }

    public void recordSuccess(String loginId) {
        redisTemplate.delete(FAIL_KEY_PREFIX + loginId);
        redisTemplate.delete(LOCK_LEVEL_KEY_PREFIX + loginId);
    }

    public void clearLockState(String loginId) {
        redisTemplate.delete(FAIL_KEY_PREFIX + loginId);
        redisTemplate.delete(LOCK_LEVEL_KEY_PREFIX + loginId);
        redisTemplate.delete(LOCKED_KEY_PREFIX + loginId);
    }

    private long lockAndGetMinutes(String loginId) {
        String levelKey = LOCK_LEVEL_KEY_PREFIX + loginId;

        Long level = redisTemplate.opsForValue().increment(levelKey);
        redisTemplate.expire(levelKey, LOCK_LEVEL_RETENTION_HOURS, TimeUnit.HOURS);

        int index = (int) Math.min(level == null ? 1L : level, LOCK_MINUTES.length) - 1;
        long lockMinutes = LOCK_MINUTES[index];

        redisTemplate.opsForValue().set(LOCKED_KEY_PREFIX + loginId, "true", lockMinutes, TimeUnit.MINUTES);

        return lockMinutes;
    }

    private CustomException lockedException(long remainingMinutes) {
        return new CustomException(
                ErrorCode.ACCOUNT_LOCKED,
                "로그인 시도 횟수를 초과하여 계정이 일시적으로 잠겼습니다. " + remainingMinutes + "분 후 다시 시도해주세요."
        );
    }
}
```

- [ ] **Step 4: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests "com.example.demo.user.service.LoginAttemptServiceTest"`
Expected: `BUILD SUCCESSFUL`, 10 tests passed

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/demo/user/service/LoginAttemptService.java src/test/java/com/example/demo/user/service/LoginAttemptServiceTest.java
git commit -m "feat: LoginAttemptService로 로그인 실패 카운트 및 점진적 계정 잠금 구현"
```

---

## Task 3: AuthService.login() 잠금 연동

**Files:**
- Modify: `src/main/java/com/example/demo/user/service/AuthService.java`
- Test: `src/test/java/com/example/demo/user/service/AuthServiceTest.java`

**Interfaces:**
- Consumes: `LoginAttemptService.checkNotLocked/recordFailure/recordSuccess(String)` (Task 2).
- Produces: `AuthService` 생성자에 `LoginAttemptService` 파라미터가 7번째로 추가됨.

- [ ] **Step 1: 실패하는 테스트 작성 (AuthServiceTest 수정)**

`AuthServiceTest.java` 수정.

`@Mock` 필드 목록에 추가 (`ConsentService consentService` 아래):

```java
    @Mock
    private LoginAttemptService loginAttemptService;
```

`setUp()`의 생성자 호출 수정:

```java
        authService = new AuthService(userRepository, refreshTokenRepository, jwtProvider, passwordEncoder, googleOAuthClient, consentService, loginAttemptService);
```

기존 `존재하지_않는_아이디면_예외가_발생한다` 테스트 끝에 검증 추가 (없는 아이디는 카운트 안 함):

```java
        verify(loginAttemptService, never()).recordFailure(anyString());
```

기존 `비밀번호가_틀리면_예외가_발생한다` 테스트 끝에 검증 추가:

```java
        verify(loginAttemptService).recordFailure("tester01");
```

기존 `로그인에_성공하면_토큰을_발급하고_refreshToken을_저장한다` 테스트 끝에 검증 추가:

```java
        verify(loginAttemptService).recordSuccess("tester01");
```

`비밀번호가_틀리면_예외가_발생한다` 테스트 다음에 새 테스트 추가:

```java
    @Test
    void 계정이_잠겨있으면_비밀번호_검증_없이_예외가_발생한다() {
        LoginRequest request = new LoginRequest();
        request.setLoginId("tester01");
        request.setPassword("password123");

        doThrow(new CustomException(ErrorCode.ACCOUNT_LOCKED, "로그인 시도 횟수를 초과하여 계정이 일시적으로 잠겼습니다. 5분 후 다시 시도해주세요."))
                .when(loginAttemptService).checkNotLocked("tester01");

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.ACCOUNT_LOCKED);

        verify(userRepository, never()).findByLoginId(anyString());
    }
```

(`doThrow`/`never`/`anyString`은 기존 `import static org.mockito.Mockito.*`로 사용 가능.)

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests "com.example.demo.user.service.AuthServiceTest"`
Expected: FAIL (컴파일 에러 — `AuthService` 생성자에 `LoginAttemptService` 파라미터 없음)

- [ ] **Step 3: AuthService 수정**

`AuthService.java` 수정. (`LoginAttemptService`는 같은 `service` 패키지라 import 불필요.)

필드/생성자 수정:

```java
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtProvider jwtProvider;
    private final PasswordEncoder passwordEncoder;
    private final GoogleOAuthClient googleOAuthClient;
    private final ConsentService consentService;
    private final LoginAttemptService loginAttemptService;

    public AuthService(
            UserRepository userRepository,
            RefreshTokenRepository refreshTokenRepository,
            JwtProvider jwtProvider,
            PasswordEncoder passwordEncoder,
            GoogleOAuthClient googleOAuthClient,
            ConsentService consentService,
            LoginAttemptService loginAttemptService
    ) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.jwtProvider = jwtProvider;
        this.passwordEncoder = passwordEncoder;
        this.googleOAuthClient = googleOAuthClient;
        this.consentService = consentService;
        this.loginAttemptService = loginAttemptService;
    }
```

`login` 메서드를 아래로 교체:

```java
    @Transactional
    public TokenResponse login(LoginRequest request) {
        loginAttemptService.checkNotLocked(request.getLoginId());

        User user = userRepository.findByLoginId(request.getLoginId())
                .orElseThrow(() -> new CustomException(ErrorCode.INVALID_CREDENTIALS));

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            loginAttemptService.recordFailure(request.getLoginId());
            throw new CustomException(ErrorCode.INVALID_CREDENTIALS);
        }

        loginAttemptService.recordSuccess(request.getLoginId());

        refreshTokenRepository.deleteByUserAndExpiresAtBefore(user, LocalDateTime.now());

        return issueTokens(user, request.isRememberMe());
    }
```

(5회째 실패 시에는 `recordFailure`가 직접 `ACCOUNT_LOCKED` 예외를 던지므로 그 아래 `INVALID_CREDENTIALS` throw에 도달하지 않는다. `googleLogin`은 변경하지 않는다.)

- [ ] **Step 4: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests "com.example.demo.user.service.AuthServiceTest"`
Expected: `BUILD SUCCESSFUL`, 전체 통과 (기존 + 신규 1개)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/demo/user/service/AuthService.java src/test/java/com/example/demo/user/service/AuthServiceTest.java
git commit -m "feat: 로그인에 계정 잠금 확인 및 실패 기록 연동"
```

---

## Task 4: 비밀번호 재설정 시 잠금 초기화

**Files:**
- Modify: `src/main/java/com/example/demo/user/service/UserService.java`
- Test: `src/test/java/com/example/demo/user/service/UserServiceTest.java`

**Interfaces:**
- Consumes: `LoginAttemptService.clearLockState(String)` (Task 2).
- Produces: `UserService` 생성자에 `LoginAttemptService` 파라미터가 6번째로 추가됨.

- [ ] **Step 1: 실패하는 테스트 작성 (UserServiceTest 수정)**

`UserServiceTest.java` 수정.

`@Mock` 필드 목록에 추가 (`ConsentService consentService` 아래):

```java
    @Mock
    private LoginAttemptService loginAttemptService;
```

`setUp()`의 생성자 호출 수정:

```java
        userService = new UserService(userRepository, emailVerificationService, passwordEncoder, refreshTokenRepository, consentService, loginAttemptService);
```

기존 `인증됐으면_비밀번호를_암호화해서_저장하고_인증플래그를_지운다` 테스트 끝에 검증 추가:

```java
        verify(loginAttemptService).clearLockState("tester01");
```

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests "com.example.demo.user.service.UserServiceTest"`
Expected: FAIL (컴파일 에러 — `UserService` 생성자에 `LoginAttemptService` 파라미터 없음)

- [ ] **Step 3: UserService 수정**

`UserService.java` 수정. (`LoginAttemptService`는 같은 `service` 패키지라 import 불필요.)

필드/생성자 수정:

```java
    private final UserRepository userRepository;
    private final EmailVerificationService emailVerificationService;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenRepository refreshTokenRepository;
    private final ConsentService consentService;
    private final LoginAttemptService loginAttemptService;

    public UserService(
            UserRepository userRepository,
            EmailVerificationService emailVerificationService,
            PasswordEncoder passwordEncoder,
            RefreshTokenRepository refreshTokenRepository,
            ConsentService consentService,
            LoginAttemptService loginAttemptService
    ) {
        this.userRepository = userRepository;
        this.emailVerificationService = emailVerificationService;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokenRepository = refreshTokenRepository;
        this.consentService = consentService;
        this.loginAttemptService = loginAttemptService;
    }
```

`resetPassword`의 `refreshTokenRepository.deleteByUser(user);` 다음에 한 줄 추가:

```java
        refreshTokenRepository.deleteByUser(user);

        loginAttemptService.clearLockState(loginId);

        emailVerificationService.clearIdentityVerified(loginId);
```

- [ ] **Step 4: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests "com.example.demo.user.service.UserServiceTest"`
Expected: `BUILD SUCCESSFUL`, 전체 통과

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/demo/user/service/UserService.java src/test/java/com/example/demo/user/service/UserServiceTest.java
git commit -m "feat: 비밀번호 재설정 성공 시 계정 잠금 초기화"
```

---

## Task 5: 컨트롤러 423 테스트 + API 문서 + 전체 검증

**Files:**
- Test: `src/test/java/com/example/demo/user/controller/AuthApiControllerTest.java`
- Modify: `docs/API_REFERENCE.md`

**Interfaces:**
- Consumes: `CustomException(ErrorCode, String)` / `ErrorCode.ACCOUNT_LOCKED` (Task 1).

- [ ] **Step 1: 컨트롤러 423 테스트 추가**

`AuthApiControllerTest.java` import에 추가:

```java
import com.example.demo.global.exception.CustomException;
import com.example.demo.global.exception.ErrorCode;
```

`로그인_성공시_토큰을_반환한다` 테스트 다음에 추가:

```java
    @Test
    void 로그인_계정이_잠겨있으면_423과_남은시간_메시지를_반환한다() throws Exception {
        LoginRequest request = new LoginRequest();
        request.setLoginId("tester01");
        request.setPassword("password123");

        when(authService.login(any())).thenThrow(new CustomException(
                ErrorCode.ACCOUNT_LOCKED,
                "로그인 시도 횟수를 초과하여 계정이 일시적으로 잠겼습니다. 12분 후 다시 시도해주세요."
        ));

        mockMvc.perform(post("/auth/login")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isLocked())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("로그인 시도 횟수를 초과하여 계정이 일시적으로 잠겼습니다. 12분 후 다시 시도해주세요."));
    }
```

- [ ] **Step 2: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests "com.example.demo.user.controller.AuthApiControllerTest"`
Expected: `BUILD SUCCESSFUL`, 전체 통과 (구현은 Task 1~3에서 이미 완료 — 이 테스트는 `GlobalExceptionHandler`가 동적 메시지·423을 그대로 내보내는지 확인하는 계약 테스트)

- [ ] **Step 3: 전체 테스트 스위트 실행**

Run: `./gradlew test`
Expected: 전체 통과. 유일하게 허용되는 실패는 사전에 존재하던 `DemoApplicationTests.contextLoads()` (환경변수 `VWORLD_API_KEY` 미설정, 이 Phase와 무관).

- [ ] **Step 4: API_REFERENCE.md 로그인 섹션 갱신**

`docs/API_REFERENCE.md` 5번(로그인) 섹션의 불릿 목록을 아래로 교체:

```markdown
- 실패: `INVALID_CREDENTIALS` (401) — 아이디/비번 중 뭐가 틀렸는지는 구분 안 해줌(보안상 의도된 동작)
- 실패: `ACCOUNT_LOCKED` (**423**) — 비밀번호 5회 오입력 시 계정이 일시 잠금됩니다. 잠금 시간은 1번째 5분 → 2번째 15분 → 3번째부터 30분으로 늘어나며, 시간이 지나면 자동 해제됩니다. `message`에 남은 시간이 포함됩니다(예: "로그인 시도 횟수를 초과하여 계정이 일시적으로 잠겼습니다. 12분 후 다시 시도해주세요."). **잠금 중에는 올바른 비밀번호를 입력해도 423이 반환됩니다.** 프론트는 423 응답 시 message를 그대로 안내하고, "비밀번호 재설정" (10→11→13번 흐름) 버튼을 함께 보여주세요 — 재설정에 성공하면 잠금이 즉시 해제됩니다.
- `rememberMe: true`면 refreshToken 유효기간이 14일, `false`면 짧게(세션성)
- 구글 로그인은 비밀번호가 없으므로 잠금 대상이 아닙니다.
```

- [ ] **Step 5: 에러 코드 표에 추가**

`## 에러 코드 전체 목록` 표의 마지막 행 다음에 추가:

```markdown
| 계정 잠금 (로그인 5회 실패) | 423 | 로그인 시도 횟수를 초과하여 계정이 일시적으로 잠겼습니다. N분 후 다시 시도해주세요. |
```

- [ ] **Step 6: Commit**

```bash
git add src/test/java/com/example/demo/user/controller/AuthApiControllerTest.java docs/API_REFERENCE.md
git commit -m "test/docs: 계정 잠금 423 응답 계약 테스트 및 API 문서 추가"
```
