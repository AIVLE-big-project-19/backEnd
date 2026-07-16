# 구글 소셜 로그인 (Phase 3) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Google OAuth2 Authorization Code를 백엔드가 직접 코드-토큰 교환/사용자정보 조회까지 처리하여, 기존 로그인과 동일한 형식(JWT `accessToken`/`refreshToken`)을 반환하는 `POST /auth/google/login` API를 구현한다.

**Architecture:** 프론트엔드가 Google 동의화면으로 리다이렉트하고 돌려받은 `authorization code`를 백엔드로 전달하면, 백엔드는 (1) Google 토큰 엔드포인트(`https://oauth2.googleapis.com/token`)에 code를 교환해 `access_token`을 얻고, (2) Google 사용자정보 엔드포인트(`https://www.googleapis.com/oauth2/v3/userinfo`)를 호출해 `sub`(고유ID)/`email`/`name`을 얻는다. 이후 `email` 기준으로 기존 `User`를 조회하여 있으면 로그인, 없으면 `provider=GOOGLE`로 자동 회원가입 후 로그인 처리한다. Spring Security의 `oauth2Login` 리다이렉트 플로우는 사용하지 않고, 기존 Phase 1/2와 동일하게 순수 REST + JSON 응답 패턴을 유지한다. Google API 호출은 Spring의 `RestClient`(스프링 6.1+ 표준, 별도 의존성 불필요)를 사용한다.

**Tech Stack:** Spring Boot 4.1.0(기존 유지), Spring Web `RestClient`, JUnit 5 + Mockito(단위 테스트), `MockRestServiceServer`(외부 HTTP 호출 테스트), MockMvc(컨트롤러 테스트).

## Global Constraints

- `context-path: /api`가 전역 설정되어 있음 → 컨트롤러 `@RequestMapping`은 `/api`를 붙이지 않고 상대 경로로 작성 (`/auth/google/login`이 실제로는 `/api/auth/google/login`).
- 신규 예외 클래스를 만들지 않는다. 모든 실패는 기존 `CustomException(ErrorCode)` + `ErrorCode` enum 추가 항목으로 표현한다.
- 모든 성공 응답은 기존 `ApiResponse.success(SuccessCode, data)` 패턴을 그대로 쓴다. 새 응답 포맷을 만들지 않는다.
- `User` 엔티티는 변경하지 않는다 — `Provider.GOOGLE`, `providerId` 컬럼이 이미 존재하고, `loginId`/`password` 컬럼은 이미 nullable이라 구글 전용 계정(아이디/비번 없음)을 그대로 저장할 수 있다.
- `SecurityConfig`는 변경하지 않는다 — `anyRequest().permitAll()`이 이미 걸려 있어 `/auth/google/login`도 별도 인증 없이 호출 가능하다.
- 이메일이 이미 `LOCAL` 계정으로 존재하면 자동 연동하지 않고 에러를 반환한다 (계정 탈취 방지, 후속 Phase에서 별도 연동 기능 검토).
- 구글 로그인은 "로그인상태유지" 옵션이 없다 — 항상 `rememberMe=true`로 14일짜리 refreshToken을 발급한다.
- `redirectUri`는 서버가 하드코딩하지 않고 프론트엔드가 요청 바디로 전달한다 — Google 토큰 교환 API는 최초 인가 요청 때 쓴 `redirect_uri`와 정확히 일치해야 통과하며, 여러 프론트 도메인(개발/배포)을 지원하기 위함이다.
- Google API 응답은 전용 DTO 대신 `Map<String, Object>`로 받아 필요한 키(`access_token`, `sub`, `email`, `name`)만 꺼내 쓴다 — 이번 Phase에서는 프로젝트의 Jackson 3(`tools.jackson`) 전환과 무관하게 동작하도록 하기 위해 별도 역직렬화 DTO/애너테이션을 만들지 않는다.
- **이 프로젝트는 Spring Boot 4.1.0 / Spring Framework 7 / Jackson 3 기준이다.** `@WebMvcTest`/`@AutoConfigureMockMvc`는 `org.springframework.boot.webmvc.test.autoconfigure.*` 패키지에 있고, `@MockBean`은 존재하지 않으며 `org.springframework.test.context.bean.override.mockito.MockitoBean`을 대신 쓴다. `ObjectMapper`는 `tools.jackson.databind.ObjectMapper` (Jackson 3)다. Spring Boot 3.x 시절 문서/예제의 import 경로를 그대로 베끼지 말 것.

---

## Task 1: ErrorCode / SuccessCode 확장

**Files:**
- Modify: `src/main/java/com/example/demo/global/exception/ErrorCode.java`
- Modify: `src/main/java/com/example/demo/global/response/SuccessCode.java`

**Interfaces:**
- Produces: `ErrorCode.GOOGLE_AUTH_FAILED`, `ErrorCode.EMAIL_ALREADY_REGISTERED_AS_LOCAL`, `SuccessCode.GOOGLE_LOGIN`. 이후 모든 Task가 이 이름을 그대로 참조한다.

- [ ] **Step 1: ErrorCode에 구글 로그인 관련 코드 추가**

`CHAT_REQUEST_FAILED(...)` 다음(마지막 상수는 세미콜론이 붙어있으므로 콤마로 바꾸고 이어서 추가):

```java
    CHAT_REQUEST_FAILED(HttpStatus.BAD_GATEWAY, "챗봇 응답 생성에 실패했습니다."),
    GOOGLE_AUTH_FAILED(HttpStatus.BAD_GATEWAY, "구글 인증에 실패했습니다."),
    EMAIL_ALREADY_REGISTERED_AS_LOCAL(HttpStatus.CONFLICT, "이미 일반 회원가입으로 등록된 이메일입니다. 일반 로그인을 이용해주세요.");
```

- [ ] **Step 2: SuccessCode에 구글 로그인 코드 추가**

`PASSWORD_RESET(...)` 다음(마지막 User 항목), `// Chat` 주석 앞에 추가:

```java
    PASSWORD_RESET("비밀번호가 재설정되었습니다."),
    GOOGLE_LOGIN("구글 로그인 성공"),

    // Chat
```

- [ ] **Step 3: 빌드 확인**

Run: `./gradlew compileJava`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add src/main/java/com/example/demo/global/exception/ErrorCode.java src/main/java/com/example/demo/global/response/SuccessCode.java
git commit -m "feat: 구글 로그인 에러/성공 코드 추가"
```

---

## Task 2: GoogleOAuthClient 구현 (코드-토큰 교환 + 사용자정보 조회)

**Files:**
- Modify: `build.gradle`
- Modify: `src/main/resources/application.yaml`
- Create: `src/main/java/com/example/demo/user/oauth/GoogleUserInfo.java`
- Create: `src/main/java/com/example/demo/user/oauth/GoogleOAuthClient.java`
- Test: `src/test/java/com/example/demo/user/oauth/GoogleOAuthClientTest.java`

**Interfaces:**
- Consumes: `ErrorCode.GOOGLE_AUTH_FAILED` (Task 1).
- Produces: `GoogleUserInfo(String providerId, String email, String name)` (getter 스타일: `getProviderId()`/`getEmail()`/`getName()`), `GoogleOAuthClient.fetchUserInfo(String code, String redirectUri): GoogleUserInfo` — Task 3이 그대로 호출한다. 설정 키 `google.client-id`, `google.client-secret`.

- [ ] **Step 1: build.gradle에 테스트 의존성 추가**

`dependencies` 블록의 `testImplementation 'org.springframework.boot:spring-boot-starter-webmvc-test'` 다음 줄에 추가:

```gradle
	testImplementation 'org.springframework.boot:spring-boot-starter-webmvc-test'
	testImplementation 'org.springframework:spring-test'
```

- [ ] **Step 2: application.yaml에 google 설정 추가**

파일 맨 끝(`jwt:` 블록 다음)에 추가:

```yaml

google:
  client-id: ${GOOGLE_CLIENT_ID:changeme.apps.googleusercontent.com}
  client-secret: ${GOOGLE_CLIENT_SECRET:changeme-google-client-secret}
```

- [ ] **Step 3: GoogleUserInfo DTO 작성**

`src/main/java/com/example/demo/user/oauth/GoogleUserInfo.java`:

```java
package com.example.demo.user.oauth;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class GoogleUserInfo {

    private String providerId;

    private String email;

    private String name;
}
```

- [ ] **Step 4: 실패하는 테스트 작성**

`src/test/java/com/example/demo/user/oauth/GoogleOAuthClientTest.java`:

```java
package com.example.demo.user.oauth;

import com.example.demo.global.exception.CustomException;
import com.example.demo.global.exception.ErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GoogleOAuthClientTest {

    private MockRestServiceServer mockServer;
    private GoogleOAuthClient googleOAuthClient;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        mockServer = MockRestServiceServer.bindTo(builder).build();
        googleOAuthClient = new GoogleOAuthClient(builder, "test-client-id", "test-client-secret");
    }

    @Test
    void 인가코드를_사용자정보로_교환한다() {
        mockServer.expect(requestTo("https://oauth2.googleapis.com/token"))
                .andRespond(withSuccess(
                        "{\"access_token\":\"google-access-token\",\"token_type\":\"Bearer\"}",
                        MediaType.APPLICATION_JSON
                ));

        mockServer.expect(requestTo("https://www.googleapis.com/oauth2/v3/userinfo"))
                .andExpect(header("Authorization", "Bearer google-access-token"))
                .andRespond(withSuccess(
                        "{\"sub\":\"1234567890\",\"email\":\"tester@gmail.com\",\"name\":\"테스터\"}",
                        MediaType.APPLICATION_JSON
                ));

        GoogleUserInfo result = googleOAuthClient.fetchUserInfo("auth-code", "http://localhost:5173/oauth/google/callback");

        assertThat(result.getProviderId()).isEqualTo("1234567890");
        assertThat(result.getEmail()).isEqualTo("tester@gmail.com");
        assertThat(result.getName()).isEqualTo("테스터");

        mockServer.verify();
    }

    @Test
    void 토큰_교환에_실패하면_예외가_발생한다() {
        mockServer.expect(requestTo("https://oauth2.googleapis.com/token"))
                .andRespond(withBadRequest());

        assertThatThrownBy(() -> googleOAuthClient.fetchUserInfo("bad-code", "http://localhost:5173/oauth/google/callback"))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.GOOGLE_AUTH_FAILED);
    }

    @Test
    void 사용자정보_조회에_실패하면_예외가_발생한다() {
        mockServer.expect(requestTo("https://oauth2.googleapis.com/token"))
                .andRespond(withSuccess(
                        "{\"access_token\":\"google-access-token\",\"token_type\":\"Bearer\"}",
                        MediaType.APPLICATION_JSON
                ));

        mockServer.expect(requestTo("https://www.googleapis.com/oauth2/v3/userinfo"))
                .andRespond(withBadRequest());

        assertThatThrownBy(() -> googleOAuthClient.fetchUserInfo("auth-code", "http://localhost:5173/oauth/google/callback"))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.GOOGLE_AUTH_FAILED);
    }
}
```

- [ ] **Step 5: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests "com.example.demo.user.oauth.GoogleOAuthClientTest"`
Expected: FAIL (컴파일 에러 — `GoogleOAuthClient` 클래스가 아직 없음)

- [ ] **Step 6: GoogleOAuthClient 구현**

`src/main/java/com/example/demo/user/oauth/GoogleOAuthClient.java`:

```java
package com.example.demo.user.oauth;

import com.example.demo.global.exception.CustomException;
import com.example.demo.global.exception.ErrorCode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Map;

@Component
public class GoogleOAuthClient {

    private static final String TOKEN_URI = "https://oauth2.googleapis.com/token";
    private static final String USERINFO_URI = "https://www.googleapis.com/oauth2/v3/userinfo";

    private final RestClient restClient;
    private final String clientId;
    private final String clientSecret;

    public GoogleOAuthClient(
            RestClient.Builder restClientBuilder,
            @Value("${google.client-id}") String clientId,
            @Value("${google.client-secret}") String clientSecret
    ) {
        this.restClient = restClientBuilder.build();
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    public GoogleUserInfo fetchUserInfo(String code, String redirectUri) {
        String accessToken = exchangeCodeForAccessToken(code, redirectUri);
        return fetchUserInfo(accessToken);
    }

    private String exchangeCodeForAccessToken(String code, String redirectUri) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("code", code);
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("redirect_uri", redirectUri);
        form.add("grant_type", "authorization_code");

        Map<String, Object> response = post(TOKEN_URI, form);

        if (response == null || response.get("access_token") == null) {
            throw new CustomException(ErrorCode.GOOGLE_AUTH_FAILED);
        }

        return response.get("access_token").toString();
    }

    private GoogleUserInfo fetchUserInfo(String accessToken) {
        Map<String, Object> response = getUserInfo(accessToken);

        if (response == null || response.get("sub") == null || response.get("email") == null) {
            throw new CustomException(ErrorCode.GOOGLE_AUTH_FAILED);
        }

        Object name = response.getOrDefault("name", "");

        return GoogleUserInfo.builder()
                .providerId(response.get("sub").toString())
                .email(response.get("email").toString())
                .name(name.toString())
                .build();
    }

    private Map<String, Object> post(String uri, MultiValueMap<String, String> form) {
        try {
            return restClient.post()
                    .uri(uri)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {
                    });
        } catch (RestClientException e) {
            throw new CustomException(ErrorCode.GOOGLE_AUTH_FAILED);
        }
    }

    private Map<String, Object> getUserInfo(String accessToken) {
        try {
            return restClient.get()
                    .uri(USERINFO_URI)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {
                    });
        } catch (RestClientException e) {
            throw new CustomException(ErrorCode.GOOGLE_AUTH_FAILED);
        }
    }
}
```

- [ ] **Step 7: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests "com.example.demo.user.oauth.GoogleOAuthClientTest"`
Expected: `BUILD SUCCESSFUL`, 3 tests passed

- [ ] **Step 8: Commit**

```bash
git add build.gradle src/main/resources/application.yaml src/main/java/com/example/demo/user/oauth src/test/java/com/example/demo/user/oauth
git commit -m "feat: GoogleOAuthClient로 구글 코드-토큰 교환 및 사용자정보 조회 구현"
```

---

## Task 3: AuthService.googleLogin() 구현

**Files:**
- Modify: `src/main/java/com/example/demo/user/service/AuthService.java`
- Test: `src/test/java/com/example/demo/user/service/AuthServiceTest.java`

**Interfaces:**
- Consumes: `GoogleOAuthClient.fetchUserInfo(String, String): GoogleUserInfo` (Task 2), `ErrorCode.EMAIL_ALREADY_REGISTERED_AS_LOCAL` (Task 1), 기존 `issueTokens(User, boolean)`.
- Produces: `AuthService.googleLogin(String code, String redirectUri): TokenResponse` — Task 4의 컨트롤러가 그대로 호출한다.

- [ ] **Step 1: 실패하는 테스트 작성 (AuthServiceTest에 추가)**

`AuthServiceTest.java` 상단 import에 추가:

```java
import com.example.demo.user.oauth.GoogleOAuthClient;
import com.example.demo.user.oauth.GoogleUserInfo;
```

`@Mock` 필드 목록에 추가 (`PasswordEncoder passwordEncoder` 아래):

```java
    @Mock
    private GoogleOAuthClient googleOAuthClient;
```

`setUp()`의 `authService = new AuthService(...)` 호출을 수정:

```java
    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        authService = new AuthService(userRepository, refreshTokenRepository, jwtProvider, passwordEncoder, googleOAuthClient);
    }
```

클래스 맨 아래(`logout하면_저장된_refreshToken을_삭제한다` 테스트 다음)에 추가:

```java

    @Test
    void 신규_구글_사용자면_자동으로_회원가입하고_토큰을_발급한다() {
        GoogleUserInfo googleUserInfo = GoogleUserInfo.builder()
                .providerId("google-sub-1")
                .email("newgoogle@example.com")
                .name("구글사용자")
                .build();

        when(googleOAuthClient.fetchUserInfo("auth-code", "http://localhost:5173/callback")).thenReturn(googleUserInfo);
        when(userRepository.findByEmail("newgoogle@example.com")).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> {
            User saved = invocation.getArgument(0);
            saved.setId(10L);
            return saved;
        });
        when(jwtProvider.generateAccessToken(10L, Role.USER)).thenReturn("access-token");
        when(jwtProvider.generateRefreshToken(10L)).thenReturn("refresh-token");
        when(jwtProvider.getRefreshTokenValidityMs(true)).thenReturn(1_209_600_000L);

        TokenResponse response = authService.googleLogin("auth-code", "http://localhost:5173/callback");

        assertThat(response.getAccessToken()).isEqualTo("access-token");
        assertThat(response.getRefreshToken()).isEqualTo("refresh-token");

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        User createdUser = userCaptor.getValue();
        assertThat(createdUser.getEmail()).isEqualTo("newgoogle@example.com");
        assertThat(createdUser.getName()).isEqualTo("구글사용자");
        assertThat(createdUser.getProvider()).isEqualTo(Provider.GOOGLE);
        assertThat(createdUser.getProviderId()).isEqualTo("google-sub-1");
        assertThat(createdUser.getLoginId()).isNull();
        assertThat(createdUser.getPassword()).isNull();
        assertThat(createdUser.getRole()).isEqualTo(Role.USER);
    }

    @Test
    void 기존_구글_사용자면_재가입하지_않고_토큰을_발급한다() {
        User existing = User.builder()
                .id(20L)
                .email("existing@example.com")
                .name("기존구글사용자")
                .provider(Provider.GOOGLE)
                .providerId("google-sub-2")
                .role(Role.USER)
                .build();

        GoogleUserInfo googleUserInfo = GoogleUserInfo.builder()
                .providerId("google-sub-2")
                .email("existing@example.com")
                .name("기존구글사용자")
                .build();

        when(googleOAuthClient.fetchUserInfo("auth-code", "http://localhost:5173/callback")).thenReturn(googleUserInfo);
        when(userRepository.findByEmail("existing@example.com")).thenReturn(Optional.of(existing));
        when(jwtProvider.generateAccessToken(20L, Role.USER)).thenReturn("access-token");
        when(jwtProvider.generateRefreshToken(20L)).thenReturn("refresh-token");
        when(jwtProvider.getRefreshTokenValidityMs(true)).thenReturn(1_209_600_000L);

        TokenResponse response = authService.googleLogin("auth-code", "http://localhost:5173/callback");

        assertThat(response.getAccessToken()).isEqualTo("access-token");
        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void 이미_로컬_계정으로_가입된_이메일이면_예외가_발생한다() {
        User localUser = User.builder()
                .id(30L)
                .loginId("localuser01")
                .email("local@example.com")
                .password("ENCODED")
                .name("로컬사용자")
                .provider(Provider.LOCAL)
                .role(Role.USER)
                .build();

        GoogleUserInfo googleUserInfo = GoogleUserInfo.builder()
                .providerId("google-sub-3")
                .email("local@example.com")
                .name("로컬사용자")
                .build();

        when(googleOAuthClient.fetchUserInfo("auth-code", "http://localhost:5173/callback")).thenReturn(googleUserInfo);
        when(userRepository.findByEmail("local@example.com")).thenReturn(Optional.of(localUser));

        assertThatThrownBy(() -> authService.googleLogin("auth-code", "http://localhost:5173/callback"))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.EMAIL_ALREADY_REGISTERED_AS_LOCAL);

        verify(userRepository, never()).save(any(User.class));
    }
```

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests "com.example.demo.user.service.AuthServiceTest"`
Expected: FAIL (컴파일 에러 — `AuthService`에 `googleLogin` 메서드와 새 생성자 인자가 없음)

- [ ] **Step 3: AuthService에 googleLogin 구현**

`AuthService.java` import 블록에 추가:

```java
import com.example.demo.user.entity.Provider;
import com.example.demo.user.entity.Role;
import com.example.demo.user.oauth.GoogleOAuthClient;
import com.example.demo.user.oauth.GoogleUserInfo;
```

필드 및 생성자를 아래처럼 교체:

```java
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtProvider jwtProvider;
    private final PasswordEncoder passwordEncoder;
    private final GoogleOAuthClient googleOAuthClient;

    public AuthService(
            UserRepository userRepository,
            RefreshTokenRepository refreshTokenRepository,
            JwtProvider jwtProvider,
            PasswordEncoder passwordEncoder,
            GoogleOAuthClient googleOAuthClient
    ) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.jwtProvider = jwtProvider;
        this.passwordEncoder = passwordEncoder;
        this.googleOAuthClient = googleOAuthClient;
    }
```

`login(...)` 메서드 다음에 새 메서드 추가:

```java
    @Transactional
    public TokenResponse googleLogin(String code, String redirectUri) {
        GoogleUserInfo googleUserInfo = googleOAuthClient.fetchUserInfo(code, redirectUri);

        User user = userRepository.findByEmail(googleUserInfo.getEmail()).orElse(null);

        if (user == null) {
            user = User.builder()
                    .email(googleUserInfo.getEmail())
                    .name(googleUserInfo.getName())
                    .provider(Provider.GOOGLE)
                    .providerId(googleUserInfo.getProviderId())
                    .role(Role.USER)
                    .build();
            user = userRepository.save(user);
        } else if (user.getProvider() != Provider.GOOGLE) {
            throw new CustomException(ErrorCode.EMAIL_ALREADY_REGISTERED_AS_LOCAL);
        }

        refreshTokenRepository.deleteByUserAndExpiresAtBefore(user, LocalDateTime.now());

        return issueTokens(user, true);
    }
```

- [ ] **Step 4: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests "com.example.demo.user.service.AuthServiceTest"`
Expected: `BUILD SUCCESSFUL`, 전체 테스트 통과 (기존 7개 + 신규 3개)

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/demo/user/service/AuthService.java src/test/java/com/example/demo/user/service/AuthServiceTest.java
git commit -m "feat: AuthService에 구글 로그인(자동 회원가입 포함) 로직 추가"
```

---

## Task 4: POST /auth/google/login 엔드포인트

**Files:**
- Create: `src/main/java/com/example/demo/user/dto/GoogleLoginRequest.java`
- Modify: `src/main/java/com/example/demo/user/controller/AuthApiController.java`
- Test: `src/test/java/com/example/demo/user/controller/AuthApiControllerTest.java`

**Interfaces:**
- Consumes: `AuthService.googleLogin(String, String): TokenResponse` (Task 3), `SuccessCode.GOOGLE_LOGIN` (Task 1).
- Produces: `POST /auth/google/login` — Request `{ "code": "...", "redirectUri": "..." }`, Response `{ "data": { "accessToken": "...", "refreshToken": "..." } }` (기존 `/auth/login`과 동일 포맷).

- [ ] **Step 1: GoogleLoginRequest DTO 작성**

`src/main/java/com/example/demo/user/dto/GoogleLoginRequest.java`:

```java
package com.example.demo.user.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class GoogleLoginRequest {

    @NotBlank(message = "인가 코드는 필수입니다.")
    private String code;

    @NotBlank(message = "redirectUri는 필수입니다.")
    private String redirectUri;
}
```

- [ ] **Step 2: 실패하는 컨트롤러 테스트 작성 (AuthApiControllerTest에 추가)**

`AuthApiControllerTest.java` import에 추가:

```java
import com.example.demo.user.dto.GoogleLoginRequest;
```

파일 마지막 `}` 앞(마지막 테스트 다음)에 추가:

```java

    @Test
    void 구글_로그인_성공시_토큰을_반환한다() throws Exception {
        GoogleLoginRequest request = new GoogleLoginRequest();
        request.setCode("auth-code");
        request.setRedirectUri("http://localhost:5173/oauth/google/callback");

        when(authService.googleLogin("auth-code", "http://localhost:5173/oauth/google/callback")).thenReturn(
                TokenResponse.builder().accessToken("access").refreshToken("refresh").build()
        );

        mockMvc.perform(post("/auth/google/login")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").value("access"))
                .andExpect(jsonPath("$.data.refreshToken").value("refresh"));
    }

    @Test
    void 구글_로그인_code가_없으면_400을_반환한다() throws Exception {
        mockMvc.perform(post("/auth/google/login")
                        .contentType("application/json")
                        .content("{\"redirectUri\":\"http://localhost:5173/oauth/google/callback\"}"))
                .andExpect(status().isBadRequest());
    }
```

- [ ] **Step 3: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests "com.example.demo.user.controller.AuthApiControllerTest"`
Expected: FAIL (`POST /auth/google/login`이 존재하지 않아 404)

- [ ] **Step 4: 컨트롤러에 엔드포인트 추가**

`AuthApiController.java`의 `login(...)` 메서드 다음에 추가:

```java
    @PostMapping("/google/login")
    public ApiResponse<TokenResponse> googleLogin(@Valid @RequestBody GoogleLoginRequest request) {
        TokenResponse response = authService.googleLogin(request.getCode(), request.getRedirectUri());

        return ApiResponse.success(SuccessCode.GOOGLE_LOGIN, response);
    }
```

(`import com.example.demo.user.dto.*;`로 와일드카드 임포트 중이므로 `GoogleLoginRequest` 별도 import는 필요 없다.)

- [ ] **Step 5: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests "com.example.demo.user.controller.AuthApiControllerTest"`
Expected: `BUILD SUCCESSFUL`, 전체 테스트 통과

- [ ] **Step 6: 전체 테스트 스위트 실행**

Run: `./gradlew test`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 7: Commit**

```bash
git add src/main/java/com/example/demo/user/dto/GoogleLoginRequest.java src/main/java/com/example/demo/user/controller/AuthApiController.java src/test/java/com/example/demo/user/controller/AuthApiControllerTest.java
git commit -m "feat: POST /auth/google/login 엔드포인트 추가"
```

---

## Task 5: API 레퍼런스 문서 갱신

**Files:**
- Modify: `docs/API_REFERENCE.md`

**Interfaces:**
- Consumes: 없음 (문서만 갱신).

- [ ] **Step 1: 제목을 Phase 1+2+3으로 갱신**

1번째 줄:

```markdown
# 로그인/회원가입 API 레퍼런스 (Phase 1 + 2 + 3)
```

- [ ] **Step 2: 14번 엔드포인트로 구글 로그인 문서 추가**

13번(`비밀번호 재설정`) 섹션과 `## 에러 코드 전체 목록` 사이에 추가:

```markdown

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
```

- [ ] **Step 3: 에러 코드 표에 추가**

`## 에러 코드 전체 목록` 표의 마지막 행 다음에 추가:

```markdown
| 입력값 검증 실패 (`@Valid`) | 400 | 필드별 메시지가 `data`에 `{필드명: 메시지}` 형태로 들어옴 |
| 이미 일반 회원가입된 이메일로 구글 로그인 시도 | 409 | 이미 일반 회원가입으로 등록된 이메일입니다. 일반 로그인을 이용해주세요. |
| 구글 인증 실패(코드 만료/재사용, 구글 API 오류) | 502 | 구글 인증에 실패했습니다. |
```

- [ ] **Step 4: "아직 없는 것" 목록에서 구글 소셜 로그인 제거**

`## 아직 없는 것 (다음 Phase 예정)` 섹션을:

```markdown
## 아직 없는 것 (다음 Phase 예정)

- 관리자 로그인/권한 관리
- 엔드포인트 인증 강제(현재는 토큰 없이도 모든 API 호출 가능)
```

로 교체 (기존 "구글 소셜 로그인" 항목 삭제).

- [ ] **Step 5: Commit**

```bash
git add docs/API_REFERENCE.md
git commit -m "docs: 구글 로그인 API 문서 추가"
```
