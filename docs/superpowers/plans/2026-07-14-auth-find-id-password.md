# 아이디/비밀번호 찾기 (Phase 2) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 아이디 찾기(이메일 인증 → 마스킹된 아이디 조회)와 비밀번호 찾기/재설정(아이디+이메일 인증 → 새 비밀번호 설정) REST API를 구현한다.

**Architecture:** Phase 1에서 만든 `EmailVerificationService`(이메일 인증코드 발송/검증)를 재사용·확장하고, `UserService`에 아이디/비밀번호 찾기 메서드를 추가한다. 새 서비스 클래스나 새 예외/응답 패턴은 만들지 않는다 — 기존 `AuthApiController`에 엔드포인트만 추가한다.

**Tech Stack:** Phase 1과 동일 (Spring Boot 4.1.0, Spring Framework 7, Jackson 3, JUnit5 + Mockito, MockMvc).

## Global Constraints

- 이 프로젝트는 **Spring Boot 4.1.0 / Spring Framework 7 / Jackson 3** 기준이다. `@WebMvcTest`/`@AutoConfigureMockMvc`는 `org.springframework.boot.webmvc.test.autoconfigure.*` 패키지에 있고, `@MockBean`은 존재하지 않으며 `org.springframework.test.context.bean.override.mockito.MockitoBean`을 쓴다. `ObjectMapper`는 `tools.jackson.databind.ObjectMapper` (Jackson 3)다. (Phase 1 Task 10에서 이 문제로 한 번 막혔던 적이 있으니 처음부터 정확히 쓸 것.)
- `context-path: /api`가 전역 설정되어 있으므로 컨트롤러의 `@RequestMapping`/`@PostMapping`/`@GetMapping`은 `/api`를 붙이지 않고 상대 경로로 작성한다.
- 신규 예외 클래스, 신규 응답 포맷을 만들지 않는다. 기존 `CustomException(ErrorCode)` + `ApiResponse.success(SuccessCode, data)` 패턴을 그대로 쓴다.
- 신규 서비스 클래스를 만들지 않는다. 아이디/비밀번호 찾기 로직은 기존 `UserService`에, 이메일 인증코드 발송/검증 관련 저수준 로직은 기존 `EmailVerificationService`에 추가한다 (설계 스펙 `docs/superpowers/specs/2026-07-14-auth-design.md` 섹션 2의 패키지 구조에 이미 명시된 배치).
- Redis 키 `identity-verified:{loginId}` (TTL 10분) — 아이디/비밀번호 찾기 인증 완료 여부. 회원가입용 `email-verified:{email}` (TTL 30분)과는 별개의 키/용도다.
- `EmailVerificationService.verifyCode(email, code)`는 Phase 1에서 이미 구현되어 있고 회원가입 전용으로 성공 시 `email-verified:{email}` 플래그를 세팅한다. 이 메서드의 기존 동작(및 기존 테스트)을 깨뜨리면 안 된다 — 아이디/비밀번호 찾기는 이 메서드를 직접 쓰지 않고, 아래 Task 2에서 만드는 `verifyCodeOnly(email, code)`(플래그를 세팅하지 않는 버전)를 쓴다.
- 아이디 찾기는 `provider=LOCAL`인 계정(즉 `loginId`가 null이 아닌 계정)에만 유효하다. `loginId`가 없는(GOOGLE) 계정의 이메일로 아이디 찾기를 시도하면 계정 존재 여부를 흘리지 않기 위해 일반 `USER_NOT_FOUND`로 처리한다.
- 비밀번호 찾기/재설정에서 `loginId`+`email` 일치 여부를 코드 검증보다 먼저 확인한다 (불일치 시 `EmailVerificationService`의 시도횟수 카운터를 건드리지 않기 위함 — 다른 사람 이메일로 온 코드를 loginId만 바꿔가며 무차별 대입하는 걸 막음).

---

## Task 1: ErrorCode / SuccessCode 확장

**Files:**
- Modify: `src/main/java/com/example/demo/global/exception/ErrorCode.java`
- Modify: `src/main/java/com/example/demo/global/response/SuccessCode.java`

**Interfaces:**
- Produces: `ErrorCode.USER_NOT_FOUND`, `ErrorCode.IDENTITY_NOT_VERIFIED`, `SuccessCode.FIND_ID_CODE_SENT`, `SuccessCode.FIND_ID_FOUND`, `SuccessCode.PASSWORD_CODE_SENT`, `SuccessCode.PASSWORD_CODE_VERIFIED`, `SuccessCode.PASSWORD_VERIFICATION_STATUS_CHECKED`, `SuccessCode.PASSWORD_RESET` — 이후 모든 Task가 이 상수들을 이름으로 참조한다.

이 Task는 순수 설정 변경이라 TDD 대상이 아니다. 컴파일 확인만 한다.

- [ ] **Step 1: ErrorCode에 2개 상수 추가**

`src/main/java/com/example/demo/global/exception/ErrorCode.java`의 마지막 상수(`INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "로그인이 만료되었습니다. 다시 로그인해주세요.")`) 뒤, 세미콜론 앞에 아래 두 줄을 추가한다 (기존 마지막 줄의 세미콜론을 쉼표로 바꾸고 이어 붙인다):

```java
    INVALID_CREDENTIALS(HttpStatus.UNAUTHORIZED, "아이디 또는 비밀번호가 일치하지 않습니다."),
    INVALID_REFRESH_TOKEN(HttpStatus.UNAUTHORIZED, "로그인이 만료되었습니다. 다시 로그인해주세요."),
    USER_NOT_FOUND(HttpStatus.NOT_FOUND, "일치하는 회원 정보를 찾을 수 없습니다."),
    IDENTITY_NOT_VERIFIED(HttpStatus.FORBIDDEN, "본인 인증이 필요합니다.");
```

(위 블록은 `INVALID_CREDENTIALS`부터 마지막 줄까지 통째로 교체하는 것이다 — 기존 두 줄 + 신규 두 줄.)

- [ ] **Step 2: SuccessCode에 6개 상수 추가**

`src/main/java/com/example/demo/global/response/SuccessCode.java`의 마지막 상수(`USER_LOGOUT("로그아웃 되었습니다.")`) 를 아래로 교체한다:

```java
    USER_LOGOUT("로그아웃 되었습니다."),
    FIND_ID_CODE_SENT("인증번호가 발송되었습니다."),
    FIND_ID_FOUND("아이디 조회 성공"),
    PASSWORD_CODE_SENT("인증번호가 발송되었습니다."),
    PASSWORD_CODE_VERIFIED("인증이 완료되었습니다."),
    PASSWORD_VERIFICATION_STATUS_CHECKED("인증 상태 조회 성공"),
    PASSWORD_RESET("비밀번호가 재설정되었습니다.");
```

- [ ] **Step 3: 컴파일 확인**

Run: `./gradlew compileJava`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: 커밋**

```bash
git add src/main/java/com/example/demo/global/exception/ErrorCode.java src/main/java/com/example/demo/global/response/SuccessCode.java
git commit -m "feat: 아이디/비밀번호 찾기 관련 ErrorCode/SuccessCode 추가"
```

---

## Task 2: EmailVerificationService — verifyCodeOnly 분리 + identity-verified 플래그

**Files:**
- Modify: `src/main/java/com/example/demo/user/service/EmailVerificationService.java`
- Test: `src/test/java/com/example/demo/user/service/EmailVerificationServiceTest.java`

**Interfaces:**
- Consumes: 없음 (Phase 1에서 이미 존재하는 클래스 확장)
- Produces: `EmailVerificationService.verifyCodeOnly(String email, String code): void` (불일치 시 `CustomException(ErrorCode.INVALID_VERIFICATION_CODE)`, 일치 시 코드/시도횟수 키만 삭제하고 아무 플래그도 세팅하지 않음), `setIdentityVerified(String loginId): void`, `isIdentityVerified(String loginId): boolean`, `clearIdentityVerified(String loginId): void` — Task 3, 4, 5가 이 4개 메서드를 그대로 쓴다.

**중요:** 기존 `verifyCode(String email, String code)`의 외부 동작(회원가입용, 성공 시 `email-verified:{email}` 플래그 세팅)은 절대 바뀌면 안 된다. 기존 `EmailVerificationServiceTest`의 7개 테스트가 전부 그대로 통과해야 한다 — 이번 Task는 `verifyCode`의 내부 구현을 `verifyCodeOnly`를 호출하도록 리팩터링하는 것뿐이다.

- [ ] **Step 1: 새 테스트 작성 (RED)**

`src/test/java/com/example/demo/user/service/EmailVerificationServiceTest.java`의 마지막 테스트 메서드(`메일_발송_실패하면_Redis에_저장되지_않고_예외가_전파된다`) 뒤, 클래스를 닫는 `}` 앞에 아래 6개 테스트를 추가한다:

```java

    @Test
    void verifyCodeOnly_성공하면_email_verified_플래그는_세팅하지_않는다() {
        when(valueOperations.get("email-code:test@example.com")).thenReturn("123456");

        emailVerificationService.verifyCodeOnly("test@example.com", "123456");

        verify(valueOperations, never()).set(eq("email-verified:test@example.com"), anyString(), anyLong(), any(TimeUnit.class));
        verify(redisTemplate).delete("email-code:test@example.com");
        verify(redisTemplate).delete("email-code-attempts:test@example.com");
    }

    @Test
    void verifyCodeOnly_불일치하면_예외가_발생한다() {
        when(valueOperations.get("email-code:test@example.com")).thenReturn("123456");
        when(valueOperations.increment("email-code-attempts:test@example.com")).thenReturn(1L);

        assertThatThrownBy(() -> emailVerificationService.verifyCodeOnly("test@example.com", "000000"))
                .isInstanceOf(CustomException.class);
    }

    @Test
    void setIdentityVerified_호출하면_10분_TTL로_저장한다() {
        emailVerificationService.setIdentityVerified("tester01");

        verify(valueOperations).set(eq("identity-verified:tester01"), eq("true"), eq(10L), eq(TimeUnit.MINUTES));
    }

    @Test
    void isIdentityVerified_플래그가_있으면_true를_반환한다() {
        when(redisTemplate.hasKey("identity-verified:tester01")).thenReturn(true);

        assertThat(emailVerificationService.isIdentityVerified("tester01")).isTrue();
    }

    @Test
    void isIdentityVerified_플래그가_없으면_false를_반환한다() {
        when(redisTemplate.hasKey("identity-verified:tester01")).thenReturn(false);

        assertThat(emailVerificationService.isIdentityVerified("tester01")).isFalse();
    }

    @Test
    void clearIdentityVerified_호출하면_키를_삭제한다() {
        emailVerificationService.clearIdentityVerified("tester01");

        verify(redisTemplate).delete("identity-verified:tester01");
    }
```

- [ ] **Step 2: 테스트 실행하여 실패 확인**

Run: `./gradlew test --tests "com.example.demo.user.service.EmailVerificationServiceTest"`
Expected: 컴파일 실패 — `verifyCodeOnly`, `setIdentityVerified`, `isIdentityVerified`, `clearIdentityVerified` 심볼을 찾을 수 없음

- [ ] **Step 3: EmailVerificationService.java 수정**

`src/main/java/com/example/demo/user/service/EmailVerificationService.java`에서 `VERIFIED_KEY_PREFIX` 선언 바로 아래에 새 prefix 상수를 추가:

```java
    private static final String VERIFIED_KEY_PREFIX = "email-verified:";
    private static final String IDENTITY_VERIFIED_KEY_PREFIX = "identity-verified:";
```

기존 `verifyCode` 메서드 전체를 아래로 교체한다:

```java
    public void verifyCode(String email, String code) {
        verifyCodeOnly(email, code);
        redisTemplate.opsForValue().set(VERIFIED_KEY_PREFIX + email, "true", 30, TimeUnit.MINUTES);
    }

    public void verifyCodeOnly(String email, String code) {
        String codeKey = CODE_KEY_PREFIX + email;
        String attemptsKey = ATTEMPTS_KEY_PREFIX + email;
        String savedCode = redisTemplate.opsForValue().get(codeKey);

        if (savedCode == null || !savedCode.equals(code)) {
            Long attempts = redisTemplate.opsForValue().increment(attemptsKey);
            redisTemplate.expire(attemptsKey, 5, TimeUnit.MINUTES);

            if (attempts != null && attempts >= MAX_ATTEMPTS) {
                redisTemplate.delete(codeKey);
                redisTemplate.delete(attemptsKey);
            }

            throw new CustomException(ErrorCode.INVALID_VERIFICATION_CODE);
        }

        redisTemplate.delete(codeKey);
        redisTemplate.delete(attemptsKey);
    }
```

`isVerified`/`clearVerified` 메서드 뒤(`generateCode` private 메서드 앞)에 아래 3개 메서드를 추가:

```java
    public void setIdentityVerified(String loginId) {
        redisTemplate.opsForValue().set(IDENTITY_VERIFIED_KEY_PREFIX + loginId, "true", 10, TimeUnit.MINUTES);
    }

    public boolean isIdentityVerified(String loginId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(IDENTITY_VERIFIED_KEY_PREFIX + loginId));
    }

    public void clearIdentityVerified(String loginId) {
        redisTemplate.delete(IDENTITY_VERIFIED_KEY_PREFIX + loginId);
    }
```

- [ ] **Step 4: 전체 테스트 실행하여 통과 확인 (기존 테스트 회귀 포함)**

Run: `./gradlew test --tests "com.example.demo.user.service.EmailVerificationServiceTest"`
Expected: 13개 테스트(기존 7개 + 신규 6개) 전부 PASS

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/example/demo/user/service/EmailVerificationService.java src/test/java/com/example/demo/user/service/EmailVerificationServiceTest.java
git commit -m "feat: EmailVerificationService에 verifyCodeOnly, identity-verified 플래그 메서드 추가"
```

---

## Task 3: UserService — 아이디 찾기 (findIdSendCode, findIdVerifyCode)

**Files:**
- Create: `src/main/java/com/example/demo/user/dto/FindIdResponse.java`
- Modify: `src/main/java/com/example/demo/user/service/UserService.java`
- Test: `src/test/java/com/example/demo/user/service/UserServiceTest.java`

**Interfaces:**
- Consumes: `EmailVerificationService.sendCode(String)`, `EmailVerificationService.verifyCodeOnly(String, String)`, `EmailVerificationService.setIdentityVerified(String)` (Task 2), `UserRepository.findByEmail(String): Optional<User>` (Phase 1)
- Produces: `UserService.findIdSendCode(String email): void`, `UserService.findIdVerifyCode(String email, String code): FindIdResponse` — Task 6이 컨트롤러에서 그대로 호출한다.

- [ ] **Step 1: FindIdResponse DTO 작성**

`src/main/java/com/example/demo/user/dto/FindIdResponse.java`:

```java
package com.example.demo.user.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class FindIdResponse {

    private String maskedLoginId;

    private LocalDateTime createdAt;
}
```

- [ ] **Step 2: 실패하는 테스트 작성 (RED)**

`src/test/java/com/example/demo/user/service/UserServiceTest.java` 마지막 테스트 뒤, 클래스를 닫는 `}` 앞에 추가 (파일 상단에 이미 `import static org.mockito.Mockito.*;` 등 필요한 정적 임포트가 있으면 그대로 재사용, `com.example.demo.user.entity.Provider`와 `java.time.LocalDateTime`, `java.util.Optional` import가 없다면 파일 상단 import 블록에 추가):

```java

    @Test
    void 등록된_이메일이면_아이디찾기_인증코드를_발송한다() {
        User user = User.builder()
                .loginId("tester01")
                .email("tester01@example.com")
                .provider(Provider.LOCAL)
                .role(Role.USER)
                .build();
        when(userRepository.findByEmail("tester01@example.com")).thenReturn(Optional.of(user));

        userService.findIdSendCode("tester01@example.com");

        verify(emailVerificationService).sendCode("tester01@example.com");
    }

    @Test
    void 등록되지_않은_이메일이면_아이디찾기_시_예외가_발생한다() {
        when(userRepository.findByEmail("nouser@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.findIdSendCode("nouser@example.com"))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.USER_NOT_FOUND);

        verify(emailVerificationService, never()).sendCode(anyString());
    }

    @Test
    void 구글계정_이메일이면_아이디찾기_시_예외가_발생한다() {
        User googleUser = User.builder()
                .loginId(null)
                .email("google-user@example.com")
                .provider(Provider.GOOGLE)
                .role(Role.USER)
                .build();
        when(userRepository.findByEmail("google-user@example.com")).thenReturn(Optional.of(googleUser));

        assertThatThrownBy(() -> userService.findIdSendCode("google-user@example.com"))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.USER_NOT_FOUND);

        verify(emailVerificationService, never()).sendCode(anyString());
    }

    @Test
    void 인증코드가_일치하면_마스킹된_아이디와_가입일을_반환한다() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 1, 1, 12, 0);
        User user = mock(User.class);
        when(user.getLoginId()).thenReturn("tester01");
        when(user.getCreatedAt()).thenReturn(createdAt);
        when(userRepository.findByEmail("tester01@example.com")).thenReturn(Optional.of(user));

        FindIdResponse response = userService.findIdVerifyCode("tester01@example.com", "123456");

        assertThat(response.getMaskedLoginId()).isEqualTo("te******");
        assertThat(response.getCreatedAt()).isEqualTo(createdAt);
        verify(emailVerificationService).verifyCodeOnly("tester01@example.com", "123456");
        verify(emailVerificationService).setIdentityVerified("tester01");
    }

    @Test
    void 인증코드가_불일치하면_아이디찾기_시_예외가_발생한다() {
        doThrow(new CustomException(ErrorCode.INVALID_VERIFICATION_CODE))
                .when(emailVerificationService).verifyCodeOnly("tester01@example.com", "000000");

        assertThatThrownBy(() -> userService.findIdVerifyCode("tester01@example.com", "000000"))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_VERIFICATION_CODE);

        verify(emailVerificationService, never()).setIdentityVerified(anyString());
    }
```

- [ ] **Step 3: 테스트 실행하여 실패 확인**

Run: `./gradlew test --tests "com.example.demo.user.service.UserServiceTest"`
Expected: 컴파일 실패 — `findIdSendCode`, `findIdVerifyCode` 심볼을 찾을 수 없음

- [ ] **Step 4: UserService.java에 메서드 추가**

`src/main/java/com/example/demo/user/service/UserService.java`의 `signup` 메서드 뒤, 클래스를 닫는 `}` 앞에 추가한다 (파일 상단 import에 `com.example.demo.user.dto.FindIdResponse` 추가 필요):

```java

    public void findIdSendCode(String email) {
        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null || user.getLoginId() == null) {
            throw new CustomException(ErrorCode.USER_NOT_FOUND);
        }

        emailVerificationService.sendCode(email);
    }

    public FindIdResponse findIdVerifyCode(String email, String code) {
        emailVerificationService.verifyCodeOnly(email, code);

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        emailVerificationService.setIdentityVerified(user.getLoginId());

        return FindIdResponse.builder()
                .maskedLoginId(maskLoginId(user.getLoginId()))
                .createdAt(user.getCreatedAt())
                .build();
    }

    private String maskLoginId(String loginId) {
        int visibleLength = Math.min(2, loginId.length());
        String visible = loginId.substring(0, visibleLength);
        String masked = "*".repeat(loginId.length() - visibleLength);
        return visible + masked;
    }
```

- [ ] **Step 5: 테스트 실행하여 통과 확인**

Run: `./gradlew test --tests "com.example.demo.user.service.UserServiceTest"`
Expected: 전부 PASS (기존 5개 + 신규 5개 = 10개)

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/example/demo/user/dto/FindIdResponse.java src/main/java/com/example/demo/user/service/UserService.java src/test/java/com/example/demo/user/service/UserServiceTest.java
git commit -m "feat: 아이디 찾기(findIdSendCode, findIdVerifyCode) 구현"
```

---

## Task 4: UserService — 비밀번호 찾기 인증 (passwordSendCode, passwordVerifyCode, isIdentityVerified)

**Files:**
- Modify: `src/main/java/com/example/demo/user/service/UserService.java`
- Test: `src/test/java/com/example/demo/user/service/UserServiceTest.java`

**Interfaces:**
- Consumes: `EmailVerificationService.sendCode`, `verifyCodeOnly`, `setIdentityVerified`, `isIdentityVerified` (Task 2), `UserRepository.findByLoginId(String): Optional<User>` (Phase 1)
- Produces: `UserService.passwordSendCode(String loginId, String email): void`, `UserService.passwordVerifyCode(String loginId, String email, String code): void`, `UserService.isIdentityVerified(String loginId): boolean` — Task 6이 컨트롤러에서 그대로 호출한다. Task 5(`resetPassword`)도 같은 파일에 이어서 추가되므로 이 Task의 메서드들과 나란히 위치한다.

- [ ] **Step 1: 실패하는 테스트 작성 (RED)**

`src/test/java/com/example/demo/user/service/UserServiceTest.java`에 Task 3에서 추가한 테스트들 뒤, 클래스를 닫는 `}` 앞에 추가:

```java

    @Test
    void 아이디와_이메일이_일치하면_비밀번호찾기_인증코드를_발송한다() {
        User user = User.builder()
                .loginId("tester01")
                .email("tester01@example.com")
                .provider(Provider.LOCAL)
                .role(Role.USER)
                .build();
        when(userRepository.findByLoginId("tester01")).thenReturn(Optional.of(user));

        userService.passwordSendCode("tester01", "tester01@example.com");

        verify(emailVerificationService).sendCode("tester01@example.com");
    }

    @Test
    void 아이디가_존재하지_않으면_비밀번호찾기_코드발송_시_예외가_발생한다() {
        when(userRepository.findByLoginId("nouser")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.passwordSendCode("nouser", "nouser@example.com"))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.USER_NOT_FOUND);

        verify(emailVerificationService, never()).sendCode(anyString());
    }

    @Test
    void 이메일이_아이디와_일치하지_않으면_비밀번호찾기_코드발송_시_예외가_발생한다() {
        User user = User.builder()
                .loginId("tester01")
                .email("real-owner@example.com")
                .provider(Provider.LOCAL)
                .role(Role.USER)
                .build();
        when(userRepository.findByLoginId("tester01")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> userService.passwordSendCode("tester01", "attacker@example.com"))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.USER_NOT_FOUND);

        verify(emailVerificationService, never()).sendCode(anyString());
    }

    @Test
    void 아이디_이메일_코드가_모두_일치하면_비밀번호찾기_인증플래그를_세팅한다() {
        User user = User.builder()
                .loginId("tester01")
                .email("tester01@example.com")
                .provider(Provider.LOCAL)
                .role(Role.USER)
                .build();
        when(userRepository.findByLoginId("tester01")).thenReturn(Optional.of(user));

        userService.passwordVerifyCode("tester01", "tester01@example.com", "123456");

        verify(emailVerificationService).verifyCodeOnly("tester01@example.com", "123456");
        verify(emailVerificationService).setIdentityVerified("tester01");
    }

    @Test
    void 아이디가_존재하지_않으면_코드확인_없이_비밀번호찾기_인증이_실패한다() {
        when(userRepository.findByLoginId("tester01")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.passwordVerifyCode("tester01", "tester01@example.com", "123456"))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.USER_NOT_FOUND);

        verify(emailVerificationService, never()).verifyCodeOnly(anyString(), anyString());
        verify(emailVerificationService, never()).setIdentityVerified(anyString());
    }

    @Test
    void 코드가_불일치하면_비밀번호찾기_인증이_실패한다() {
        User user = User.builder()
                .loginId("tester01")
                .email("tester01@example.com")
                .provider(Provider.LOCAL)
                .role(Role.USER)
                .build();
        when(userRepository.findByLoginId("tester01")).thenReturn(Optional.of(user));
        doThrow(new CustomException(ErrorCode.INVALID_VERIFICATION_CODE))
                .when(emailVerificationService).verifyCodeOnly("tester01@example.com", "000000");

        assertThatThrownBy(() -> userService.passwordVerifyCode("tester01", "tester01@example.com", "000000"))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_VERIFICATION_CODE);

        verify(emailVerificationService, never()).setIdentityVerified(anyString());
    }

    @Test
    void isIdentityVerified는_EmailVerificationService에_위임한다() {
        when(emailVerificationService.isIdentityVerified("tester01")).thenReturn(true);

        assertThat(userService.isIdentityVerified("tester01")).isTrue();
    }
```

- [ ] **Step 2: 테스트 실행하여 실패 확인**

Run: `./gradlew test --tests "com.example.demo.user.service.UserServiceTest"`
Expected: 컴파일 실패 — `passwordSendCode`, `passwordVerifyCode`, `isIdentityVerified` 심볼을 찾을 수 없음

- [ ] **Step 3: UserService.java에 메서드 추가**

`src/main/java/com/example/demo/user/service/UserService.java`의 `findIdVerifyCode`/`maskLoginId` 메서드 뒤, 클래스를 닫는 `}` 앞에 추가:

```java

    public void passwordSendCode(String loginId, String email) {
        User user = userRepository.findByLoginId(loginId).orElse(null);
        if (user == null || !user.getEmail().equals(email)) {
            throw new CustomException(ErrorCode.USER_NOT_FOUND);
        }

        emailVerificationService.sendCode(email);
    }

    public void passwordVerifyCode(String loginId, String email, String code) {
        User user = userRepository.findByLoginId(loginId).orElse(null);
        if (user == null || !user.getEmail().equals(email)) {
            throw new CustomException(ErrorCode.USER_NOT_FOUND);
        }

        emailVerificationService.verifyCodeOnly(email, code);
        emailVerificationService.setIdentityVerified(loginId);
    }

    public boolean isIdentityVerified(String loginId) {
        return emailVerificationService.isIdentityVerified(loginId);
    }
```

- [ ] **Step 4: 테스트 실행하여 통과 확인**

Run: `./gradlew test --tests "com.example.demo.user.service.UserServiceTest"`
Expected: 전부 PASS (Task 3까지 10개 + 신규 7개 = 17개)

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/example/demo/user/service/UserService.java src/test/java/com/example/demo/user/service/UserServiceTest.java
git commit -m "feat: 비밀번호 찾기 인증(passwordSendCode, passwordVerifyCode, isIdentityVerified) 구현"
```

---

## Task 5: UserService — 비밀번호 재설정 (resetPassword)

**Files:**
- Modify: `src/main/java/com/example/demo/user/service/UserService.java`
- Test: `src/test/java/com/example/demo/user/service/UserServiceTest.java`

**Interfaces:**
- Consumes: `EmailVerificationService.isIdentityVerified`, `clearIdentityVerified` (Task 2), `UserRepository.findByLoginId`, `save` (Phase 1), `PasswordEncoder.encode` (Phase 1 — 이미 `UserService`에 필드로 주입되어 있음)
- Produces: `UserService.resetPassword(String loginId, String newPassword): void` — Task 6이 컨트롤러에서 그대로 호출한다.

- [ ] **Step 1: 실패하는 테스트 작성 (RED)**

`src/test/java/com/example/demo/user/service/UserServiceTest.java`에 Task 4에서 추가한 테스트들 뒤, 클래스를 닫는 `}` 앞에 추가:

```java

    @Test
    void 인증되지_않았으면_비밀번호_재설정_시_예외가_발생한다() {
        when(emailVerificationService.isIdentityVerified("tester01")).thenReturn(false);

        assertThatThrownBy(() -> userService.resetPassword("tester01", "newPassword123"))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.IDENTITY_NOT_VERIFIED);

        verify(userRepository, never()).save(any());
    }

    @Test
    void 인증됐으면_비밀번호를_암호화해서_저장하고_인증플래그를_지운다() {
        User user = User.builder()
                .loginId("tester01")
                .email("tester01@example.com")
                .password("old-encoded")
                .provider(Provider.LOCAL)
                .role(Role.USER)
                .build();
        when(emailVerificationService.isIdentityVerified("tester01")).thenReturn(true);
        when(userRepository.findByLoginId("tester01")).thenReturn(Optional.of(user));
        when(passwordEncoder.encode("newPassword123")).thenReturn("new-encoded");

        userService.resetPassword("tester01", "newPassword123");

        assertThat(user.getPassword()).isEqualTo("new-encoded");
        verify(userRepository).save(user);
        verify(emailVerificationService).clearIdentityVerified("tester01");
    }

    @Test
    void 인증됐지만_사용자가_없으면_비밀번호_재설정_시_예외가_발생한다() {
        when(emailVerificationService.isIdentityVerified("tester01")).thenReturn(true);
        when(userRepository.findByLoginId("tester01")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.resetPassword("tester01", "newPassword123"))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.USER_NOT_FOUND);

        verify(userRepository, never()).save(any());
        verify(emailVerificationService, never()).clearIdentityVerified(anyString());
    }
```

- [ ] **Step 2: 테스트 실행하여 실패 확인**

Run: `./gradlew test --tests "com.example.demo.user.service.UserServiceTest"`
Expected: 컴파일 실패 — `resetPassword` 심볼을 찾을 수 없음

- [ ] **Step 3: UserService.java에 메서드 추가**

`src/main/java/com/example/demo/user/service/UserService.java`의 `isIdentityVerified` 메서드 뒤, 클래스를 닫는 `}` 앞에 추가:

```java

    public void resetPassword(String loginId, String newPassword) {
        if (!emailVerificationService.isIdentityVerified(loginId)) {
            throw new CustomException(ErrorCode.IDENTITY_NOT_VERIFIED);
        }

        User user = userRepository.findByLoginId(loginId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        emailVerificationService.clearIdentityVerified(loginId);
    }
```

- [ ] **Step 4: 테스트 실행하여 통과 확인**

Run: `./gradlew test --tests "com.example.demo.user.service.UserServiceTest"`
Expected: 전부 PASS (Task 4까지 17개 + 신규 3개 = 20개)

- [ ] **Step 5: 커밋**

```bash
git add src/main/java/com/example/demo/user/service/UserService.java src/test/java/com/example/demo/user/service/UserServiceTest.java
git commit -m "feat: 비밀번호 재설정(resetPassword) 구현"
```

---

## Task 6: AuthApiController — 아이디/비밀번호 찾기 엔드포인트 6개

**Files:**
- Create: `src/main/java/com/example/demo/user/dto/FindIdSendCodeRequest.java`
- Create: `src/main/java/com/example/demo/user/dto/FindIdVerifyCodeRequest.java`
- Create: `src/main/java/com/example/demo/user/dto/PasswordSendCodeRequest.java`
- Create: `src/main/java/com/example/demo/user/dto/PasswordVerifyCodeRequest.java`
- Create: `src/main/java/com/example/demo/user/dto/VerificationStatusResponse.java`
- Create: `src/main/java/com/example/demo/user/dto/ResetPasswordRequest.java`
- Modify: `src/main/java/com/example/demo/user/controller/AuthApiController.java`
- Test: `src/test/java/com/example/demo/user/controller/AuthApiControllerTest.java`

**Interfaces:**
- Consumes: `UserService.findIdSendCode/findIdVerifyCode/passwordSendCode/passwordVerifyCode/isIdentityVerified/resetPassword` (Task 3, 4, 5)
- Produces: 최종 REST 엔드포인트 6개 — 이 Phase의 최종 산출물. 이후 Task(프론트 연동)가 이 URL들을 그대로 호출한다.

- [ ] **Step 1: 6개 DTO 작성**

`src/main/java/com/example/demo/user/dto/FindIdSendCodeRequest.java`:
```java
package com.example.demo.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class FindIdSendCodeRequest {

    @NotBlank(message = "이메일은 필수입니다.")
    @Email(message = "이메일 형식이 올바르지 않습니다.")
    private String email;
}
```

`src/main/java/com/example/demo/user/dto/FindIdVerifyCodeRequest.java`:
```java
package com.example.demo.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class FindIdVerifyCodeRequest {

    @NotBlank(message = "이메일은 필수입니다.")
    @Email(message = "이메일 형식이 올바르지 않습니다.")
    private String email;

    @NotBlank(message = "인증번호는 필수입니다.")
    private String code;
}
```

`src/main/java/com/example/demo/user/dto/PasswordSendCodeRequest.java`:
```java
package com.example.demo.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PasswordSendCodeRequest {

    @NotBlank(message = "아이디는 필수입니다.")
    private String loginId;

    @NotBlank(message = "이메일은 필수입니다.")
    @Email(message = "이메일 형식이 올바르지 않습니다.")
    private String email;
}
```

`src/main/java/com/example/demo/user/dto/PasswordVerifyCodeRequest.java`:
```java
package com.example.demo.user.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PasswordVerifyCodeRequest {

    @NotBlank(message = "아이디는 필수입니다.")
    private String loginId;

    @NotBlank(message = "이메일은 필수입니다.")
    @Email(message = "이메일 형식이 올바르지 않습니다.")
    private String email;

    @NotBlank(message = "인증번호는 필수입니다.")
    private String code;
}
```

`src/main/java/com/example/demo/user/dto/VerificationStatusResponse.java`:
```java
package com.example.demo.user.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class VerificationStatusResponse {

    private boolean verified;
}
```

`src/main/java/com/example/demo/user/dto/ResetPasswordRequest.java`:
```java
package com.example.demo.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ResetPasswordRequest {

    @NotBlank(message = "아이디는 필수입니다.")
    private String loginId;

    @NotBlank(message = "새 비밀번호는 필수입니다.")
    @Size(min = 8, max = 50, message = "비밀번호는 8자 이상 입력하세요.")
    private String newPassword;
}
```

- [ ] **Step 2: 실패하는 컨트롤러 테스트 작성 (RED)**

`src/test/java/com/example/demo/user/controller/AuthApiControllerTest.java`의 `로그아웃은_200을_반환한다` 테스트 뒤, 클래스를 닫는 `}` 앞에 추가. 파일 상단 import 블록에 `import com.example.demo.user.dto.FindIdResponse;`와 `import java.time.LocalDateTime;` 두 줄을 추가한다 (둘 다 현재 파일에 없음):

```java

    @Test
    void 아이디찾기_인증코드_발송은_200을_반환한다() throws Exception {
        mockMvc.perform(post("/auth/find-id/send-code")
                        .contentType("application/json")
                        .content("{\"email\":\"tester01@example.com\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void 아이디찾기_인증코드_확인은_마스킹된_아이디를_반환한다() throws Exception {
        LocalDateTime createdAt = LocalDateTime.of(2026, 1, 1, 12, 0);
        when(userService.findIdVerifyCode("tester01@example.com", "123456")).thenReturn(
                FindIdResponse.builder().maskedLoginId("te******").createdAt(createdAt).build()
        );

        mockMvc.perform(post("/auth/find-id/verify-code")
                        .contentType("application/json")
                        .content("{\"email\":\"tester01@example.com\",\"code\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.maskedLoginId").value("te******"));
    }

    @Test
    void 비밀번호찾기_인증코드_발송은_200을_반환한다() throws Exception {
        mockMvc.perform(post("/auth/password/send-code")
                        .contentType("application/json")
                        .content("{\"loginId\":\"tester01\",\"email\":\"tester01@example.com\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void 비밀번호찾기_인증코드_확인은_200을_반환한다() throws Exception {
        mockMvc.perform(post("/auth/password/verify-code")
                        .contentType("application/json")
                        .content("{\"loginId\":\"tester01\",\"email\":\"tester01@example.com\",\"code\":\"123456\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void 비밀번호_재설정_인증상태_조회는_verified_값을_반환한다() throws Exception {
        when(userService.isIdentityVerified("tester01")).thenReturn(true);

        mockMvc.perform(get("/auth/password/verification-status").param("loginId", "tester01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.verified").value(true));
    }

    @Test
    void 비밀번호_재설정은_200을_반환한다() throws Exception {
        mockMvc.perform(post("/auth/password/reset")
                        .contentType("application/json")
                        .content("{\"loginId\":\"tester01\",\"newPassword\":\"newPassword123\"}"))
                .andExpect(status().isOk());
    }
```

- [ ] **Step 3: 테스트 실행하여 실패 확인**

Run: `./gradlew test --tests "com.example.demo.user.controller.AuthApiControllerTest"`
Expected: 컴파일 실패 또는 404 — 엔드포인트가 아직 없음

- [ ] **Step 4: AuthApiController.java에 엔드포인트 추가**

`src/main/java/com/example/demo/user/controller/AuthApiController.java`의 `logout` 메서드 뒤, 클래스를 닫는 `}` 앞에 추가:

```java

    @PostMapping("/find-id/send-code")
    public ApiResponse<Void> findIdSendCode(@Valid @RequestBody FindIdSendCodeRequest request) {
        userService.findIdSendCode(request.getEmail());

        return ApiResponse.success(SuccessCode.FIND_ID_CODE_SENT);
    }

    @PostMapping("/find-id/verify-code")
    public ApiResponse<FindIdResponse> findIdVerifyCode(@Valid @RequestBody FindIdVerifyCodeRequest request) {
        FindIdResponse response = userService.findIdVerifyCode(request.getEmail(), request.getCode());

        return ApiResponse.success(SuccessCode.FIND_ID_FOUND, response);
    }

    @PostMapping("/password/send-code")
    public ApiResponse<Void> passwordSendCode(@Valid @RequestBody PasswordSendCodeRequest request) {
        userService.passwordSendCode(request.getLoginId(), request.getEmail());

        return ApiResponse.success(SuccessCode.PASSWORD_CODE_SENT);
    }

    @PostMapping("/password/verify-code")
    public ApiResponse<Void> passwordVerifyCode(@Valid @RequestBody PasswordVerifyCodeRequest request) {
        userService.passwordVerifyCode(request.getLoginId(), request.getEmail(), request.getCode());

        return ApiResponse.success(SuccessCode.PASSWORD_CODE_VERIFIED);
    }

    @GetMapping("/password/verification-status")
    public ApiResponse<VerificationStatusResponse> verificationStatus(@RequestParam String loginId) {
        boolean verified = userService.isIdentityVerified(loginId);

        return ApiResponse.success(
                SuccessCode.PASSWORD_VERIFICATION_STATUS_CHECKED,
                VerificationStatusResponse.builder().verified(verified).build()
        );
    }

    @PostMapping("/password/reset")
    public ApiResponse<Void> resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        userService.resetPassword(request.getLoginId(), request.getNewPassword());

        return ApiResponse.success(SuccessCode.PASSWORD_RESET);
    }
```

(`AuthApiController`는 이미 `import com.example.demo.user.dto.*;`로 와일드카드 임포트하고 있으므로 새 DTO에 대한 추가 import는 필요 없다.)

- [ ] **Step 5: 테스트 실행하여 통과 확인**

Run: `./gradlew test --tests "com.example.demo.user.controller.AuthApiControllerTest"`
Expected: 전부 PASS (기존 5개 + 신규 6개 = 11개)

- [ ] **Step 6: 커밋**

```bash
git add src/main/java/com/example/demo/user/dto/FindIdSendCodeRequest.java src/main/java/com/example/demo/user/dto/FindIdVerifyCodeRequest.java src/main/java/com/example/demo/user/dto/PasswordSendCodeRequest.java src/main/java/com/example/demo/user/dto/PasswordVerifyCodeRequest.java src/main/java/com/example/demo/user/dto/VerificationStatusResponse.java src/main/java/com/example/demo/user/dto/ResetPasswordRequest.java src/main/java/com/example/demo/user/controller/AuthApiController.java src/test/java/com/example/demo/user/controller/AuthApiControllerTest.java
git commit -m "feat: 아이디/비밀번호 찾기 REST API 엔드포인트 6개 추가"
```

---

## Task 7: 전체 테스트 실행 및 수동 통합 확인

**Files:** 없음 (검증 전용 Task)

**Interfaces:** 없음

- [ ] **Step 1: 전체 테스트 스위트 실행**

Run: `./gradlew test`
Expected: `BUILD SUCCESSFUL`, 전체 테스트 그린 (Phase 1의 39개 + 이번 Phase에서 추가된 신규 테스트 — Task 2에서 6개, Task 3에서 5개, Task 4에서 7개, Task 5에서 3개, Task 6에서 6개, 합계 27개가 추가될 것으로 예상되나 정확한 수는 실행 결과로 확인)

기존 `DemoApplicationTests`(vWorld API 키 관련)가 로컬 환경변수 미설정으로 실패한다면 — 이는 이 Phase와 무관한 기존 이슈이므로 무시하고 진행 가능. 이 Phase에서 추가/수정한 테스트 클래스(`EmailVerificationServiceTest`, `UserServiceTest`, `AuthApiControllerTest`)는 전부 통과해야 한다.

- [ ] **Step 2: (선택) 수동 통합 확인**

로컬에 MySQL, Redis가 떠 있으면 `./gradlew bootRun` 후 아래 흐름을 curl 또는 Postman으로 확인:

1. `POST /api/auth/find-id/send-code` `{"email":"<가입된 이메일>"}` → 200
2. `POST /api/auth/find-id/verify-code` `{"email":"...", "code":"<메일로 받은 코드>"}` → 200, `maskedLoginId`/`createdAt` 확인
3. `POST /api/auth/password/send-code` `{"loginId":"...", "email":"..."}` → 200
4. `POST /api/auth/password/verify-code` `{"loginId":"...", "email":"...", "code":"..."}` → 200
5. `GET /api/auth/password/verification-status?loginId=...` → `{"verified": true}`
6. `POST /api/auth/password/reset` `{"loginId":"...", "newPassword":"newPassword123"}` → 200
7. 새 비밀번호로 `POST /api/auth/login` 시도 → 정상 로그인 확인

이메일 발송 환경(Redis, `.env`의 `MAIL_USERNAME`/`MAIL_PASSWORD`)이 없으면 2~3단계는 `redis-cli SET email-code:<이메일> <6자리코드> EX 300`로 코드를 직접 넣어 대체 가능.
