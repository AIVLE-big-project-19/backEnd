# 개인정보 수집·이용 동의 (Phase 4) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 회원가입 시 필수 2(이용약관/개인정보) + 선택 1(마케팅) 동의를 수집·증빙 가능하게 저장하고, 약관 본문 제공·동의 현황 조회·마케팅 동의 변경 API를 구현한다.

**Architecture:** append-only `user_consents` 테이블(UPDATE/DELETE 없음, 미동의도 `agreed=false`로 기록, 현재 상태 = 타입별 최신 행)에 동의 이력을 남긴다. 동의 기록/조회/변경 로직은 신규 `ConsentService`로 분리해 `UserService.signup`(일반 가입)과 `AuthService.googleLogin`(구글 자동가입)이 공유한다. 약관 본문은 백엔드 클래스패스 리소스 파일(`terms/*.md`)로 관리하고 `GET /terms/{type}`으로 제공하며, 버전은 `application.yaml`의 `terms.version` 단일 값이다. 상세 설계: `docs/superpowers/specs/2026-07-16-privacy-consent-design.md`.

**Tech Stack:** Spring Boot 4.1.0(기존 유지), Spring Data JPA, JUnit 5 + Mockito(단위 테스트), MockMvc(`@WebMvcTest`, 컨트롤러 테스트).

## Global Constraints

- `context-path: /api`가 전역 설정되어 있음 → 컨트롤러 `@RequestMapping`은 `/api`를 붙이지 않고 상대 경로로 작성 (`/terms`가 실제로는 `/api/terms`).
- 신규 예외 클래스를 만들지 않는다. 실패는 기존 `CustomException(ErrorCode)` + `ErrorCode` enum 추가 항목(`TERMS_NOT_FOUND` 하나뿐)으로 표현한다. 필수 약관 미동의는 `@AssertTrue` + 기존 `@Valid` 400 패턴으로 처리한다 (새 ErrorCode 불필요).
- 모든 성공 응답은 기존 `ApiResponse.success(SuccessCode, data)` 패턴을 그대로 쓴다.
- 엔티티는 기존 `RefreshToken`과 동일하게 `global.entity.BaseEntity`를 extends하고 `@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder`를 사용한다. `BaseEntity.createdAt`이 동의/철회 시각이다.
- `user_consents`는 **append-only**: 상태 변경은 새 행 추가로만 한다. UPDATE/DELETE 금지.
- `SecurityConfig`는 변경하지 않는다 (`anyRequest().permitAll()` 유지). 인증 유저 식별은 기존 `MyPageController`와 동일하게 `@AuthenticationPrincipal Long userId`를 쓴다 (JwtAuthenticationFilter가 principal에 `Long` userId를 넣음).
- 테이블 생성은 `ddl-auto: update`가 자동 처리한다. 별도 마이그레이션 스크립트 없음. 기존 가입자 소급 마이그레이션도 없음 (동의 기록 없는 유저는 조회 시 `agreed: null`).
- **이 프로젝트는 Spring Boot 4.1.0 / Spring Framework 7 / Jackson 3 기준이다.** `@WebMvcTest`/`@AutoConfigureMockMvc`는 `org.springframework.boot.webmvc.test.autoconfigure.*` 패키지에 있고, `@MockBean`은 존재하지 않으며 `org.springframework.test.context.bean.override.mockito.MockitoBean`을 대신 쓴다. `ObjectMapper`는 `tools.jackson.databind.ObjectMapper` (Jackson 3)다. Spring Boot 3.x 시절 문서/예제의 import 경로를 그대로 베끼지 말 것.

---

## Task 1: 도메인 기반 (엔티티/리포지토리/코드/설정)

**Files:**
- Create: `src/main/java/com/example/demo/user/entity/ConsentType.java`
- Create: `src/main/java/com/example/demo/user/entity/UserConsent.java`
- Create: `src/main/java/com/example/demo/user/repository/UserConsentRepository.java`
- Modify: `src/main/java/com/example/demo/global/exception/ErrorCode.java`
- Modify: `src/main/java/com/example/demo/global/response/SuccessCode.java`
- Modify: `src/main/resources/application.yaml`

**Interfaces:**
- Produces: `ConsentType{TERMS, PRIVACY, MARKETING}`, `UserConsent` 엔티티(`user/consentType/agreed/termsVersion` 필드, Builder), `UserConsentRepository.findTopByUserAndConsentTypeOrderByIdDesc(User, ConsentType): Optional<UserConsent>`, `ErrorCode.TERMS_NOT_FOUND`, `SuccessCode.TERMS_FOUND`/`CONSENT_STATUS_FOUND`/`MARKETING_CONSENT_UPDATED`, 설정 키 `terms.version`. 이후 모든 Task가 이 이름을 그대로 참조한다.

- [ ] **Step 1: ConsentType enum 작성**

`src/main/java/com/example/demo/user/entity/ConsentType.java`:

```java
package com.example.demo.user.entity;

public enum ConsentType {
    TERMS, PRIVACY, MARKETING
}
```

- [ ] **Step 2: UserConsent 엔티티 작성**

`src/main/java/com/example/demo/user/entity/UserConsent.java`:

```java
package com.example.demo.user.entity;

import com.example.demo.global.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "user_consents")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserConsent extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ConsentType consentType;

    @Column(nullable = false)
    private boolean agreed;

    @Column(nullable = false, length = 20)
    private String termsVersion;
}
```

- [ ] **Step 3: UserConsentRepository 작성**

`src/main/java/com/example/demo/user/repository/UserConsentRepository.java`:

```java
package com.example.demo.user.repository;

import com.example.demo.user.entity.ConsentType;
import com.example.demo.user.entity.User;
import com.example.demo.user.entity.UserConsent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserConsentRepository extends JpaRepository<UserConsent, Long> {

    Optional<UserConsent> findTopByUserAndConsentTypeOrderByIdDesc(User user, ConsentType consentType);

}
```

- [ ] **Step 4: ErrorCode에 TERMS_NOT_FOUND 추가**

`ErrorCode.java`의 마지막 상수 `EMAIL_ALREADY_REGISTERED_AS_LOCAL(...)` 뒤 세미콜론을 콤마로 바꾸고 추가:

```java
    EMAIL_ALREADY_REGISTERED_AS_LOCAL(HttpStatus.CONFLICT, "이미 일반 회원가입으로 등록된 이메일입니다. 일반 로그인을 이용해주세요."),
    TERMS_NOT_FOUND(HttpStatus.NOT_FOUND, "약관을 찾을 수 없습니다.");
```

- [ ] **Step 5: SuccessCode에 3개 추가**

`SuccessCode.java`의 `GOOGLE_LOGIN("구글 로그인 성공"),` 다음, `// Chat` 주석 앞에 추가:

```java
    GOOGLE_LOGIN("구글 로그인 성공"),
    TERMS_FOUND("약관 조회 성공"),
    CONSENT_STATUS_FOUND("동의 현황 조회 성공"),
    MARKETING_CONSENT_UPDATED("마케팅 수신 동의가 변경되었습니다."),

    // Chat
```

- [ ] **Step 6: application.yaml에 terms.version 추가**

파일 맨 끝(`google:` 블록 다음)에 추가:

```yaml

terms:
  version: "1.0"
```

- [ ] **Step 7: 빌드 확인**

Run: `./gradlew compileJava`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/example/demo/user/entity/ConsentType.java src/main/java/com/example/demo/user/entity/UserConsent.java src/main/java/com/example/demo/user/repository/UserConsentRepository.java src/main/java/com/example/demo/global/exception/ErrorCode.java src/main/java/com/example/demo/global/response/SuccessCode.java src/main/resources/application.yaml
git commit -m "feat: 동의 이력 엔티티/리포지토리 및 약관 관련 코드 추가"
```

---

## Task 2: ConsentService (동의 기록/조회/변경)

**Files:**
- Create: `src/main/java/com/example/demo/user/dto/ConsentStatusResponse.java`
- Create: `src/main/java/com/example/demo/user/service/ConsentService.java`
- Test: `src/test/java/com/example/demo/user/service/ConsentServiceTest.java`

**Interfaces:**
- Consumes: `UserConsent`/`ConsentType`/`UserConsentRepository`(Task 1), 기존 `UserRepository.findById`, `ErrorCode.USER_NOT_FOUND`(기존).
- Produces: `ConsentService.recordSignupConsents(User user, boolean marketingAgreed): void`, `ConsentService.getConsentStatus(Long userId): ConsentStatusResponse`, `ConsentService.updateMarketingConsent(Long userId, boolean agreed): ConsentStatusResponse.ConsentItem`, `ConsentStatusResponse{List<ConsentItem> consents}`, `ConsentStatusResponse.ConsentItem{String type, Boolean agreed, String version, LocalDateTime agreedAt}`. Task 3/4/6이 그대로 호출한다.

- [ ] **Step 1: ConsentStatusResponse DTO 작성**

`src/main/java/com/example/demo/user/dto/ConsentStatusResponse.java`:

```java
package com.example.demo.user.dto;

import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Builder
public class ConsentStatusResponse {

    private List<ConsentItem> consents;

    @Getter
    @Builder
    public static class ConsentItem {

        private String type;

        private Boolean agreed;

        private String version;

        private LocalDateTime agreedAt;
    }
}
```

- [ ] **Step 2: 실패하는 테스트 작성**

`src/test/java/com/example/demo/user/service/ConsentServiceTest.java`:

```java
package com.example.demo.user.service;

import com.example.demo.global.exception.CustomException;
import com.example.demo.global.exception.ErrorCode;
import com.example.demo.user.dto.ConsentStatusResponse;
import com.example.demo.user.entity.ConsentType;
import com.example.demo.user.entity.Provider;
import com.example.demo.user.entity.Role;
import com.example.demo.user.entity.User;
import com.example.demo.user.entity.UserConsent;
import com.example.demo.user.repository.UserConsentRepository;
import com.example.demo.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ConsentServiceTest {

    @Mock
    private UserConsentRepository userConsentRepository;

    @Mock
    private UserRepository userRepository;

    private ConsentService consentService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        consentService = new ConsentService(userConsentRepository, userRepository, "1.0");
    }

    private User sampleUser() {
        return User.builder()
                .id(1L)
                .loginId("tester01")
                .email("tester01@example.com")
                .name("테스터")
                .provider(Provider.LOCAL)
                .role(Role.USER)
                .build();
    }

    @Test
    void 가입_동의_기록시_3개_타입_행을_모두_저장한다() {
        User user = sampleUser();

        consentService.recordSignupConsents(user, true);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<UserConsent>> captor = ArgumentCaptor.forClass(List.class);
        verify(userConsentRepository).saveAll(captor.capture());
        List<UserConsent> saved = captor.getValue();

        assertThat(saved).hasSize(3);
        assertThat(saved).extracting(UserConsent::getConsentType)
                .containsExactlyInAnyOrder(ConsentType.TERMS, ConsentType.PRIVACY, ConsentType.MARKETING);
        assertThat(saved).allSatisfy(c -> {
            assertThat(c.getUser()).isEqualTo(user);
            assertThat(c.getTermsVersion()).isEqualTo("1.0");
        });
        assertThat(saved).filteredOn(c -> c.getConsentType() == ConsentType.MARKETING)
                .allSatisfy(c -> assertThat(c.isAgreed()).isTrue());
    }

    @Test
    void 마케팅_미동의로_가입하면_MARKETING_행이_false로_저장된다() {
        User user = sampleUser();

        consentService.recordSignupConsents(user, false);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<UserConsent>> captor = ArgumentCaptor.forClass(List.class);
        verify(userConsentRepository).saveAll(captor.capture());

        assertThat(captor.getValue()).filteredOn(c -> c.getConsentType() == ConsentType.MARKETING)
                .allSatisfy(c -> assertThat(c.isAgreed()).isFalse());
        assertThat(captor.getValue()).filteredOn(c -> c.getConsentType() != ConsentType.MARKETING)
                .allSatisfy(c -> assertThat(c.isAgreed()).isTrue());
    }

    @Test
    void 동의_현황_조회시_타입별_최신_행_기준으로_반환한다() {
        User user = sampleUser();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userConsentRepository.findTopByUserAndConsentTypeOrderByIdDesc(user, ConsentType.TERMS))
                .thenReturn(Optional.of(UserConsent.builder()
                        .user(user).consentType(ConsentType.TERMS).agreed(true).termsVersion("1.0").build()));
        when(userConsentRepository.findTopByUserAndConsentTypeOrderByIdDesc(user, ConsentType.PRIVACY))
                .thenReturn(Optional.of(UserConsent.builder()
                        .user(user).consentType(ConsentType.PRIVACY).agreed(true).termsVersion("1.0").build()));
        when(userConsentRepository.findTopByUserAndConsentTypeOrderByIdDesc(user, ConsentType.MARKETING))
                .thenReturn(Optional.of(UserConsent.builder()
                        .user(user).consentType(ConsentType.MARKETING).agreed(false).termsVersion("1.0").build()));

        ConsentStatusResponse response = consentService.getConsentStatus(1L);

        assertThat(response.getConsents()).hasSize(3);
        assertThat(response.getConsents()).extracting(ConsentStatusResponse.ConsentItem::getType)
                .containsExactly("TERMS", "PRIVACY", "MARKETING");
        assertThat(response.getConsents()).filteredOn(i -> i.getType().equals("MARKETING"))
                .allSatisfy(i -> assertThat(i.getAgreed()).isFalse());
    }

    @Test
    void 동의_기록이_없는_항목은_agreed가_null이다() {
        User user = sampleUser();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userConsentRepository.findTopByUserAndConsentTypeOrderByIdDesc(eq(user), any(ConsentType.class)))
                .thenReturn(Optional.empty());

        ConsentStatusResponse response = consentService.getConsentStatus(1L);

        assertThat(response.getConsents()).hasSize(3);
        assertThat(response.getConsents()).allSatisfy(i -> {
            assertThat(i.getAgreed()).isNull();
            assertThat(i.getVersion()).isNull();
            assertThat(i.getAgreedAt()).isNull();
        });
    }

    @Test
    void 존재하지_않는_유저로_조회하면_예외가_발생한다() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> consentService.getConsentStatus(99L))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.USER_NOT_FOUND);
    }

    @Test
    void 마케팅_동의_변경시_새_행을_추가하고_변경된_상태를_반환한다() {
        User user = sampleUser();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userConsentRepository.save(any(UserConsent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ConsentStatusResponse.ConsentItem item = consentService.updateMarketingConsent(1L, true);

        ArgumentCaptor<UserConsent> captor = ArgumentCaptor.forClass(UserConsent.class);
        verify(userConsentRepository).save(captor.capture());
        UserConsent saved = captor.getValue();

        assertThat(saved.getConsentType()).isEqualTo(ConsentType.MARKETING);
        assertThat(saved.isAgreed()).isTrue();
        assertThat(saved.getTermsVersion()).isEqualTo("1.0");
        assertThat(item.getType()).isEqualTo("MARKETING");
        assertThat(item.getAgreed()).isTrue();
        verify(userConsentRepository, never()).deleteById(any());
    }

    @Test
    void 마케팅_동의_철회도_새_행_추가로_기록된다() {
        User user = sampleUser();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(userConsentRepository.save(any(UserConsent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        ConsentStatusResponse.ConsentItem item = consentService.updateMarketingConsent(1L, false);

        assertThat(item.getAgreed()).isFalse();
        verify(userConsentRepository).save(any(UserConsent.class));
    }
}
```

- [ ] **Step 3: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests "com.example.demo.user.service.ConsentServiceTest"`
Expected: FAIL (컴파일 에러 — `ConsentService` 클래스가 아직 없음)

- [ ] **Step 4: ConsentService 구현**

`src/main/java/com/example/demo/user/service/ConsentService.java`:

```java
package com.example.demo.user.service;

import com.example.demo.global.exception.CustomException;
import com.example.demo.global.exception.ErrorCode;
import com.example.demo.user.dto.ConsentStatusResponse;
import com.example.demo.user.entity.ConsentType;
import com.example.demo.user.entity.User;
import com.example.demo.user.entity.UserConsent;
import com.example.demo.user.repository.UserConsentRepository;
import com.example.demo.user.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;

@Service
public class ConsentService {

    private final UserConsentRepository userConsentRepository;
    private final UserRepository userRepository;
    private final String termsVersion;

    public ConsentService(
            UserConsentRepository userConsentRepository,
            UserRepository userRepository,
            @Value("${terms.version}") String termsVersion
    ) {
        this.userConsentRepository = userConsentRepository;
        this.userRepository = userRepository;
        this.termsVersion = termsVersion;
    }

    public void recordSignupConsents(User user, boolean marketingAgreed) {
        userConsentRepository.saveAll(List.of(
                consentRow(user, ConsentType.TERMS, true),
                consentRow(user, ConsentType.PRIVACY, true),
                consentRow(user, ConsentType.MARKETING, marketingAgreed)
        ));
    }

    public ConsentStatusResponse getConsentStatus(Long userId) {
        User user = findUser(userId);

        List<ConsentStatusResponse.ConsentItem> items = Arrays.stream(ConsentType.values())
                .map(type -> toItem(type,
                        userConsentRepository.findTopByUserAndConsentTypeOrderByIdDesc(user, type).orElse(null)))
                .toList();

        return ConsentStatusResponse.builder().consents(items).build();
    }

    @Transactional
    public ConsentStatusResponse.ConsentItem updateMarketingConsent(Long userId, boolean agreed) {
        User user = findUser(userId);

        UserConsent saved = userConsentRepository.save(consentRow(user, ConsentType.MARKETING, agreed));

        return toItem(ConsentType.MARKETING, saved);
    }

    private User findUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));
    }

    private UserConsent consentRow(User user, ConsentType type, boolean agreed) {
        return UserConsent.builder()
                .user(user)
                .consentType(type)
                .agreed(agreed)
                .termsVersion(termsVersion)
                .build();
    }

    private ConsentStatusResponse.ConsentItem toItem(ConsentType type, UserConsent consent) {
        if (consent == null) {
            return ConsentStatusResponse.ConsentItem.builder()
                    .type(type.name())
                    .build();
        }

        return ConsentStatusResponse.ConsentItem.builder()
                .type(type.name())
                .agreed(consent.isAgreed())
                .version(consent.getTermsVersion())
                .agreedAt(consent.getCreatedAt())
                .build();
    }
}
```

- [ ] **Step 5: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests "com.example.demo.user.service.ConsentServiceTest"`
Expected: `BUILD SUCCESSFUL`, 7 tests passed

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/example/demo/user/dto/ConsentStatusResponse.java src/main/java/com/example/demo/user/service/ConsentService.java src/test/java/com/example/demo/user/service/ConsentServiceTest.java
git commit -m "feat: ConsentService로 동의 기록/조회/변경 로직 구현"
```

---

## Task 3: 회원가입 동의 수집 (SignupRequest + UserService)

**Files:**
- Modify: `src/main/java/com/example/demo/user/dto/SignupRequest.java`
- Modify: `src/main/java/com/example/demo/user/service/UserService.java`
- Test: `src/test/java/com/example/demo/user/service/UserServiceTest.java`
- Test: `src/test/java/com/example/demo/user/controller/AuthApiControllerTest.java`

**Interfaces:**
- Consumes: `ConsentService.recordSignupConsents(User, boolean)` (Task 2).
- Produces: `SignupRequest`에 `termsAgreed`/`privacyAgreed`(필수, `@AssertTrue`)/`marketingAgreed`(선택) boolean 필드. `UserService` 생성자에 `ConsentService` 파라미터가 5번째로 추가됨 (Task 4의 AuthService 변경과 무관, 참고용).

- [ ] **Step 1: SignupRequest에 동의 필드 추가**

`SignupRequest.java` import에 추가:

```java
import jakarta.validation.constraints.AssertTrue;
```

클래스 맨 아래 `name` 필드 다음에 추가:

```java
    @NotBlank(message = "이름은 필수입니다.")
    private String name;

    @AssertTrue(message = "필수 약관에 동의해야 합니다.")
    private boolean termsAgreed;

    @AssertTrue(message = "필수 약관에 동의해야 합니다.")
    private boolean privacyAgreed;

    private boolean marketingAgreed;
```

- [ ] **Step 2: 실패하는 테스트 작성 (UserServiceTest 수정)**

`UserServiceTest.java` 수정:

`@Mock` 필드 목록에 추가 (`RefreshTokenRepository refreshTokenRepository` 아래):

```java
    @Mock
    private ConsentService consentService;
```

`setUp()`의 생성자 호출 수정:

```java
        userService = new UserService(userRepository, emailVerificationService, passwordEncoder, refreshTokenRepository, consentService);
```

`validRequest()` 헬퍼 수정 (필수 동의 포함):

```java
    private SignupRequest validRequest() {
        SignupRequest request = new SignupRequest();
        request.setLoginId("tester01");
        request.setEmail("tester01@example.com");
        request.setPassword("password123");
        request.setName("테스터");
        request.setTermsAgreed(true);
        request.setPrivacyAgreed(true);
        return request;
    }
```

기존 `정상_요청이면_비밀번호를_인코딩해서_저장하고_인증플래그를_지운다` 테스트에서 저장된 유저로 동의가 기록되는지 검증을 추가하기 위해, `when(passwordEncoder.encode(...))` 아래에 save 스텁 추가:

```java
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
```

그리고 테스트 끝에 검증 한 줄 추가:

```java
        verify(consentService).recordSignupConsents(any(User.class), eq(false));
```

`정상_요청이면_...` 테스트 다음에 새 테스트 추가:

```java
    @Test
    void 마케팅_동의하고_가입하면_동의값이_true로_기록된다() {
        SignupRequest request = validRequest();
        request.setMarketingAgreed(true);
        when(emailVerificationService.isVerified(request.getEmail())).thenReturn(true);
        when(userRepository.existsByLoginId(request.getLoginId())).thenReturn(false);
        when(userRepository.existsByEmail(request.getEmail())).thenReturn(false);
        when(passwordEncoder.encode(request.getPassword())).thenReturn("ENCODED");
        when(userRepository.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        userService.signup(request);

        verify(consentService).recordSignupConsents(any(User.class), eq(true));
    }
```

(파일 상단 import는 기존에 `static org.mockito.Mockito.*`가 있으므로 `eq`/`any`는 `import static org.mockito.ArgumentMatchers.any;`, `import static org.mockito.ArgumentMatchers.eq;` 추가가 필요하면 추가한다.)

- [ ] **Step 3: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests "com.example.demo.user.service.UserServiceTest"`
Expected: FAIL (컴파일 에러 — `UserService` 생성자에 `ConsentService` 파라미터 없음)

- [ ] **Step 4: UserService 수정**

`UserService.java` 수정.

import에 추가:

```java
import org.springframework.transaction.annotation.Transactional;
```

(이미 있으면 생략 — `resetPassword`가 이미 쓰고 있어 존재함)

필드/생성자 수정:

```java
    private final UserRepository userRepository;
    private final EmailVerificationService emailVerificationService;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenRepository refreshTokenRepository;
    private final ConsentService consentService;

    public UserService(
            UserRepository userRepository,
            EmailVerificationService emailVerificationService,
            PasswordEncoder passwordEncoder,
            RefreshTokenRepository refreshTokenRepository,
            ConsentService consentService
    ) {
        this.userRepository = userRepository;
        this.emailVerificationService = emailVerificationService;
        this.passwordEncoder = passwordEncoder;
        this.refreshTokenRepository = refreshTokenRepository;
        this.consentService = consentService;
    }
```

`signup` 메서드 수정 — `@Transactional` 추가, save 반환값 캡처, 동의 기록:

```java
    @Transactional
    public void signup(SignupRequest request) {
        if (!emailVerificationService.isVerified(request.getEmail())) {
            throw new CustomException(ErrorCode.EMAIL_VERIFICATION_REQUIRED);
        }

        if (userRepository.existsByLoginId(request.getLoginId())) {
            throw new CustomException(ErrorCode.DUPLICATE_LOGIN_ID);
        }

        if (userRepository.existsByEmail(request.getEmail())) {
            throw new CustomException(ErrorCode.DUPLICATE_EMAIL);
        }

        User user = User.builder()
                .loginId(request.getLoginId())
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .name(request.getName())
                .provider(Provider.LOCAL)
                .role(Role.USER)
                .build();

        User saved = userRepository.save(user);
        consentService.recordSignupConsents(saved, request.isMarketingAgreed());

        emailVerificationService.clearVerified(request.getEmail());
    }
```

- [ ] **Step 5: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests "com.example.demo.user.service.UserServiceTest"`
Expected: `BUILD SUCCESSFUL`, 전체 통과 (기존 + 신규 1개)

- [ ] **Step 6: 컨트롤러 테스트 수정 (기존 signup 테스트 + 미동의 400 케이스)**

`AuthApiControllerTest.java` 수정.

`회원가입_성공시_201을_반환한다`와 `회원가입_비밀번호가_형식에_맞지_않으면_400을_반환한다` 두 테스트의 request 세팅에 필수 동의 추가 (`request.setName("테스터");` 다음 줄):

```java
        request.setTermsAgreed(true);
        request.setPrivacyAgreed(true);
```

`회원가입_비밀번호가_형식에_맞지_않으면_400을_반환한다` 테스트 다음에 새 테스트 추가:

```java
    @Test
    void 회원가입_필수약관에_미동의하면_400을_반환한다() throws Exception {
        SignupRequest request = new SignupRequest();
        request.setLoginId("tester01");
        request.setEmail("tester01@example.com");
        request.setPassword("password1!");
        request.setName("테스터");
        request.setTermsAgreed(false);
        request.setPrivacyAgreed(true);

        mockMvc.perform(post("/auth/signup")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.termsAgreed").value("필수 약관에 동의해야 합니다."));
    }
```

- [ ] **Step 7: 컨트롤러 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests "com.example.demo.user.controller.AuthApiControllerTest"`
Expected: `BUILD SUCCESSFUL`, 전체 통과

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/example/demo/user/dto/SignupRequest.java src/main/java/com/example/demo/user/service/UserService.java src/test/java/com/example/demo/user/service/UserServiceTest.java src/test/java/com/example/demo/user/controller/AuthApiControllerTest.java
git commit -m "feat: 회원가입 시 필수/선택 동의 수집 및 이력 기록"
```

---

## Task 4: 구글 자동가입 동의 기록 (AuthService)

**Files:**
- Modify: `src/main/java/com/example/demo/user/service/AuthService.java`
- Test: `src/test/java/com/example/demo/user/service/AuthServiceTest.java`

**Interfaces:**
- Consumes: `ConsentService.recordSignupConsents(User, boolean)` (Task 2).
- Produces: `AuthService` 생성자에 `ConsentService` 파라미터가 6번째로 추가됨.

- [ ] **Step 1: 실패하는 테스트 작성 (AuthServiceTest 수정)**

`AuthServiceTest.java` 수정.

`@Mock` 필드 목록에 추가 (`GoogleOAuthClient googleOAuthClient` 아래):

```java
    @Mock
    private ConsentService consentService;
```

`setUp()`의 생성자 호출 수정:

```java
        authService = new AuthService(userRepository, refreshTokenRepository, jwtProvider, passwordEncoder, googleOAuthClient, consentService);
```

기존 `신규_구글_사용자면_자동으로_회원가입하고_토큰을_발급한다` 테스트 끝에 검증 추가:

```java
        verify(consentService).recordSignupConsents(any(User.class), eq(false));
```

기존 `기존_구글_사용자면_재가입하지_않고_토큰을_발급한다` 테스트 끝에 검증 추가:

```java
        verify(consentService, never()).recordSignupConsents(any(User.class), anyBoolean());
```

기존 `동시_회원가입_경쟁에서_패배하면_재조회한_사용자로_토큰을_발급한다` 테스트 끝에 검증 추가 (경합 패배 시 상대 스레드가 이미 기록했으므로 중복 기록 금지):

```java
        verify(consentService, never()).recordSignupConsents(any(User.class), anyBoolean());
```

(`anyBoolean`은 `import static org.mockito.ArgumentMatchers.anyBoolean;` 추가.)

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests "com.example.demo.user.service.AuthServiceTest"`
Expected: FAIL (컴파일 에러 — `AuthService` 생성자에 `ConsentService` 파라미터 없음)

- [ ] **Step 3: AuthService 수정**

`AuthService.java` 수정. (`ConsentService`는 같은 `service` 패키지라 import 불필요.)

필드/생성자 수정:

```java
    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtProvider jwtProvider;
    private final PasswordEncoder passwordEncoder;
    private final GoogleOAuthClient googleOAuthClient;
    private final ConsentService consentService;

    public AuthService(
            UserRepository userRepository,
            RefreshTokenRepository refreshTokenRepository,
            JwtProvider jwtProvider,
            PasswordEncoder passwordEncoder,
            GoogleOAuthClient googleOAuthClient,
            ConsentService consentService
    ) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.jwtProvider = jwtProvider;
        this.passwordEncoder = passwordEncoder;
        this.googleOAuthClient = googleOAuthClient;
        this.consentService = consentService;
    }
```

`googleLogin`의 신규 가입 try 블록 수정 — save 성공 직후에만 동의 기록 (경합 패배 catch 경로에서는 기록하지 않음):

```java
            try {
                user = userRepository.save(newUser);
                consentService.recordSignupConsents(user, false);
            } catch (DataIntegrityViolationException e) {
                // 동시 요청으로 인해 다른 스레드가 먼저 동일 이메일로 가입을 완료한 경우.
                // unique 제약조건 위반으로 저장에 실패했으므로, 방금 가입된 사용자를 재조회하여 처리한다.
                user = userRepository.findByEmail(googleUserInfo.getEmail())
                        .orElseThrow(() -> new CustomException(ErrorCode.GOOGLE_AUTH_FAILED));
            }
```

- [ ] **Step 4: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests "com.example.demo.user.service.AuthServiceTest"`
Expected: `BUILD SUCCESSFUL`, 전체 12개 통과

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/demo/user/service/AuthService.java src/test/java/com/example/demo/user/service/AuthServiceTest.java
git commit -m "feat: 구글 자동가입 시 필수 동의 자동 기록"
```

---

## Task 5: 약관 본문 API (GET /terms/{type})

**Files:**
- Create: `src/main/resources/terms/TERMS.md`
- Create: `src/main/resources/terms/PRIVACY.md`
- Create: `src/main/java/com/example/demo/user/dto/TermsResponse.java`
- Create: `src/main/java/com/example/demo/user/service/TermsService.java`
- Create: `src/main/java/com/example/demo/user/controller/TermsController.java`
- Test: `src/test/java/com/example/demo/user/service/TermsServiceTest.java`
- Test: `src/test/java/com/example/demo/user/controller/TermsControllerTest.java`

**Interfaces:**
- Consumes: `ConsentType`(Task 1), `ErrorCode.TERMS_NOT_FOUND`(Task 1), `SuccessCode.TERMS_FOUND`(Task 1), 설정 키 `terms.version`(Task 1).
- Produces: `TermsService.getTerms(String rawType): TermsResponse`, `TermsResponse{String type, String version, String content}`, `GET /terms/{type}` 엔드포인트.

- [ ] **Step 1: 약관 본문 리소스 파일 작성**

`src/main/resources/terms/TERMS.md`:

```markdown
# 서비스 이용약관

**시행일: 2026년 7월 16일 (버전 1.0)**

## 제1조 (목적)

이 약관은 SolarAivle(이하 "서비스")가 제공하는 서비스의 이용과 관련하여 서비스와 회원 간의 권리, 의무 및 책임사항을 규정함을 목적으로 합니다.

## 제2조 (정의)

1. "회원"이란 이 약관에 동의하고 회원가입을 완료한 자를 말합니다.
2. "계정"이란 회원 식별과 서비스 이용을 위해 회원이 설정한 아이디 또는 구글 계정 연동 정보를 말합니다.

## 제3조 (약관의 효력 및 변경)

1. 이 약관은 서비스 화면에 게시하거나 기타 방법으로 공지함으로써 효력이 발생합니다.
2. 서비스는 필요 시 관련 법령을 위반하지 않는 범위에서 이 약관을 변경할 수 있으며, 변경 시 적용일자 및 변경사유를 명시하여 사전에 공지합니다.

## 제4조 (회원가입)

1. 회원가입은 가입 희망자가 약관에 동의하고 가입 신청을 하면 서비스가 이를 승낙함으로써 성립합니다.
2. 서비스는 타인 명의 도용 등 부정한 신청에 대해 승낙을 거부하거나 사후에 이용계약을 해지할 수 있습니다.

## 제5조 (회원의 의무)

1. 회원은 계정 정보를 본인이 직접 관리해야 하며, 제3자에게 이용을 허락해서는 안 됩니다.
2. 회원은 서비스 이용 시 관련 법령, 약관, 이용안내를 준수해야 합니다.

## 제6조 (서비스의 제공 및 중단)

서비스는 시스템 점검, 장애, 천재지변 등 부득이한 사유가 있는 경우 서비스 제공을 일시 중단할 수 있습니다.

## 제7조 (계약 해지)

회원은 언제든지 서비스 내 기능 또는 문의를 통해 이용계약 해지(회원탈퇴)를 신청할 수 있습니다.
```

`src/main/resources/terms/PRIVACY.md`:

```markdown
# 개인정보처리방침 (개인정보 수집·이용 동의)

**시행일: 2026년 7월 16일 (버전 1.0)**

SolarAivle(이하 "서비스")는 정보주체의 자유와 권리 보호를 위해 「개인정보 보호법」 및 관계 법령이 정한 바를 준수하여 적법하게 개인정보를 처리하고 안전하게 관리합니다. 이에 「개인정보 보호법」 제30조에 따라 개인정보 처리에 관한 절차 및 기준을 안내하기 위해 다음과 같이 개인정보처리방침을 수립·공개합니다.

## 제1조 (개인정보의 처리 목적, 수집 항목, 보유 및 이용 기간)

서비스는 회원 관리를 위해 필요한 최소한의 개인정보만을 수집하며, 목적별 수집 항목과 보유 기간은 다음과 같습니다.

### 가. 필수 수집·이용

| 수집·이용 목적 | 개인정보 항목 | 보유 기간 |
|---|---|---|
| 회원 식별, 본인 확인, 서비스 제공 | (일반 가입) 아이디, 이메일, 비밀번호, 이름 | 회원 탈퇴 시까지 |
| 회원 식별, 본인 확인, 서비스 제공 | (구글 로그인) 이메일, 이름, 구글 계정 식별자 | 회원 탈퇴 시까지 |
| 계정 관리(아이디/비밀번호 찾기, 이메일 인증) | 이메일 | 회원 탈퇴 시까지 |

### 나. 선택 수집·이용

| 수집·이용 목적 | 개인정보 항목 | 보유 기간 |
|---|---|---|
| 서비스 소식 및 이벤트 안내(마케팅) | 이메일 | 동의 철회 또는 회원 탈퇴 시까지 |

- 서비스 이용 과정에서 접속 기록, 서비스 이용 기록이 자동으로 생성·수집될 수 있습니다.
- 서비스는 쿠키 등 개인정보 자동 수집 장치를 운영하지 않습니다.
- 법령에 특별한 규정이 있는 경우 해당 법령이 정한 기간 동안 보관합니다.

## 제2조 (개인정보의 파기 절차 및 방법)

1. 보유 기간이 경과하거나 처리 목적이 달성된 개인정보는 지체 없이 파기합니다.
2. 전자적 파일 형태로 저장된 개인정보는 복구·재생할 수 없는 방법으로 삭제합니다.

## 제3조 (개인정보의 제3자 제공)

서비스는 정보주체의 개인정보를 제3자에게 제공하지 않습니다. 다만 정보주체가 별도로 동의하거나 법률에 특별한 규정이 있는 경우는 예외로 합니다.

## 제4조 (정보주체의 권리·의무 및 행사 방법)

1. 정보주체는 언제든지 자신의 개인정보에 대한 열람·정정·삭제·처리정지 요구 등의 권리를 행사할 수 있습니다.
2. 마케팅 수신 동의는 마이페이지에서 언제든지 변경(철회)할 수 있습니다.
3. 회원 탈퇴 시 수집된 개인정보는 지체 없이 파기됩니다.

## 제5조 (개인정보의 안전성 확보 조치)

1. 비밀번호는 일방향 암호화(해시)하여 저장하며, 원문을 저장하지 않습니다.
2. 개인정보는 암호화된 통신 구간(HTTPS)을 통해 송수신합니다.
3. 개인정보처리시스템에 대한 접근 권한 관리 등 기술적 조치를 적용하고 있습니다.

## 제6조 (동의 거부 권리 및 불이익)

귀하는 개인정보 수집·이용 동의를 거부할 권리가 있습니다. 다만 필수 항목 동의를 거부할 경우 회원가입 및 서비스 이용이 제한됩니다. 마케팅 수신 동의는 선택 사항이며, 동의하지 않아도 서비스 이용에 제한이 없습니다.

## 제7조 (개인정보 보호책임자 및 문의)

- 개인정보 보호책임자: SolarAivle 운영팀
- 개인정보 관련 문의는 서비스 내 문의하기 기능을 이용해 주시기 바랍니다.

## 제8조 (권익침해 구제 방법)

개인정보 침해에 대한 신고·상담은 아래 기관에 문의할 수 있습니다.

- 개인정보분쟁조정위원회: (국번없이) 1833-6972 (www.kopico.go.kr)
- 개인정보침해신고센터: (국번없이) 118 (privacy.kisa.or.kr)
```

- [ ] **Step 2: TermsResponse DTO 작성**

`src/main/java/com/example/demo/user/dto/TermsResponse.java`:

```java
package com.example.demo.user.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class TermsResponse {

    private String type;

    private String version;

    private String content;
}
```

- [ ] **Step 3: 실패하는 서비스 테스트 작성**

`src/test/java/com/example/demo/user/service/TermsServiceTest.java`:

```java
package com.example.demo.user.service;

import com.example.demo.global.exception.CustomException;
import com.example.demo.global.exception.ErrorCode;
import com.example.demo.user.dto.TermsResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TermsServiceTest {

    private TermsService termsService;

    @BeforeEach
    void setUp() {
        termsService = new TermsService("1.0");
    }

    @Test
    void 이용약관_본문을_조회할_수_있다() {
        TermsResponse response = termsService.getTerms("TERMS");

        assertThat(response.getType()).isEqualTo("TERMS");
        assertThat(response.getVersion()).isEqualTo("1.0");
        assertThat(response.getContent()).contains("서비스 이용약관");
    }

    @Test
    void 개인정보처리방침_본문을_소문자_타입으로도_조회할_수_있다() {
        TermsResponse response = termsService.getTerms("privacy");

        assertThat(response.getType()).isEqualTo("PRIVACY");
        assertThat(response.getContent()).contains("개인정보");
    }

    @Test
    void 존재하지_않는_타입이면_예외가_발생한다() {
        assertThatThrownBy(() -> termsService.getTerms("UNKNOWN"))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.TERMS_NOT_FOUND);
    }

    @Test
    void MARKETING_타입은_본문_문서가_없으므로_예외가_발생한다() {
        assertThatThrownBy(() -> termsService.getTerms("MARKETING"))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.TERMS_NOT_FOUND);
    }
}
```

- [ ] **Step 4: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests "com.example.demo.user.service.TermsServiceTest"`
Expected: FAIL (컴파일 에러 — `TermsService` 클래스가 아직 없음)

- [ ] **Step 5: TermsService 구현**

`src/main/java/com/example/demo/user/service/TermsService.java`:

```java
package com.example.demo.user.service;

import com.example.demo.global.exception.CustomException;
import com.example.demo.global.exception.ErrorCode;
import com.example.demo.user.dto.TermsResponse;
import com.example.demo.user.entity.ConsentType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TermsService {

    private final String termsVersion;
    private final Map<ConsentType, String> contentCache = new ConcurrentHashMap<>();

    public TermsService(@Value("${terms.version}") String termsVersion) {
        this.termsVersion = termsVersion;
    }

    public TermsResponse getTerms(String rawType) {
        ConsentType type = parseType(rawType);
        String content = contentCache.computeIfAbsent(type, this::loadContent);

        return TermsResponse.builder()
                .type(type.name())
                .version(termsVersion)
                .content(content)
                .build();
    }

    private ConsentType parseType(String rawType) {
        ConsentType type;
        try {
            type = ConsentType.valueOf(rawType.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new CustomException(ErrorCode.TERMS_NOT_FOUND);
        }

        if (type == ConsentType.MARKETING) {
            throw new CustomException(ErrorCode.TERMS_NOT_FOUND);
        }

        return type;
    }

    private String loadContent(ConsentType type) {
        try {
            return new ClassPathResource("terms/" + type.name() + ".md")
                    .getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new CustomException(ErrorCode.TERMS_NOT_FOUND);
        }
    }
}
```

- [ ] **Step 6: 서비스 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests "com.example.demo.user.service.TermsServiceTest"`
Expected: `BUILD SUCCESSFUL`, 4 tests passed

- [ ] **Step 7: 실패하는 컨트롤러 테스트 작성**

`src/test/java/com/example/demo/user/controller/TermsControllerTest.java`:

```java
package com.example.demo.user.controller;

import com.example.demo.global.exception.CustomException;
import com.example.demo.global.exception.ErrorCode;
import com.example.demo.user.dto.TermsResponse;
import com.example.demo.user.service.TermsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TermsController.class)
@AutoConfigureMockMvc(addFilters = false)
class TermsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TermsService termsService;

    @Test
    void 약관_조회_성공시_본문과_버전을_반환한다() throws Exception {
        when(termsService.getTerms("TERMS")).thenReturn(
                TermsResponse.builder().type("TERMS").version("1.0").content("# 서비스 이용약관").build()
        );

        mockMvc.perform(get("/terms/TERMS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.type").value("TERMS"))
                .andExpect(jsonPath("$.data.version").value("1.0"))
                .andExpect(jsonPath("$.data.content").value("# 서비스 이용약관"));
    }

    @Test
    void 존재하지_않는_약관_타입이면_404를_반환한다() throws Exception {
        when(termsService.getTerms("UNKNOWN"))
                .thenThrow(new CustomException(ErrorCode.TERMS_NOT_FOUND));

        mockMvc.perform(get("/terms/UNKNOWN"))
                .andExpect(status().isNotFound());
    }
}
```

- [ ] **Step 8: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests "com.example.demo.user.controller.TermsControllerTest"`
Expected: FAIL (컴파일 에러 — `TermsController` 클래스가 아직 없음)

- [ ] **Step 9: TermsController 구현**

`src/main/java/com/example/demo/user/controller/TermsController.java`:

```java
package com.example.demo.user.controller;

import com.example.demo.global.response.ApiResponse;
import com.example.demo.global.response.SuccessCode;
import com.example.demo.user.dto.TermsResponse;
import com.example.demo.user.service.TermsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/terms")
@RequiredArgsConstructor
public class TermsController {

    private final TermsService termsService;

    @GetMapping("/{type}")
    public ApiResponse<TermsResponse> getTerms(@PathVariable String type) {
        return ApiResponse.success(SuccessCode.TERMS_FOUND, termsService.getTerms(type));
    }
}
```

- [ ] **Step 10: 컨트롤러 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests "com.example.demo.user.controller.TermsControllerTest"`
Expected: `BUILD SUCCESSFUL`, 2 tests passed

- [ ] **Step 11: Commit**

```bash
git add src/main/resources/terms src/main/java/com/example/demo/user/dto/TermsResponse.java src/main/java/com/example/demo/user/service/TermsService.java src/main/java/com/example/demo/user/controller/TermsController.java src/test/java/com/example/demo/user/service/TermsServiceTest.java src/test/java/com/example/demo/user/controller/TermsControllerTest.java
git commit -m "feat: 약관 본문 조회 API(GET /terms/{type}) 추가"
```

---

## Task 6: 동의 현황/변경 API (/users/me/consents)

**Files:**
- Create: `src/main/java/com/example/demo/user/dto/MarketingConsentRequest.java`
- Create: `src/main/java/com/example/demo/user/controller/ConsentController.java`
- Test: `src/test/java/com/example/demo/user/controller/ConsentControllerTest.java`

**Interfaces:**
- Consumes: `ConsentService.getConsentStatus(Long): ConsentStatusResponse`, `ConsentService.updateMarketingConsent(Long, boolean): ConsentStatusResponse.ConsentItem` (Task 2), `SuccessCode.CONSENT_STATUS_FOUND`/`MARKETING_CONSENT_UPDATED` (Task 1).
- Produces: `GET /users/me/consents`, `PUT /users/me/consents/marketing` 엔드포인트.

- [ ] **Step 1: MarketingConsentRequest DTO 작성**

`src/main/java/com/example/demo/user/dto/MarketingConsentRequest.java`:

```java
package com.example.demo.user.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class MarketingConsentRequest {

    @NotNull(message = "동의 여부는 필수입니다.")
    private Boolean agreed;
}
```

- [ ] **Step 2: 실패하는 컨트롤러 테스트 작성**

`src/test/java/com/example/demo/user/controller/ConsentControllerTest.java`:

```java
package com.example.demo.user.controller;

import com.example.demo.user.dto.ConsentStatusResponse;
import com.example.demo.user.service.ConsentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ConsentController.class)
@AutoConfigureMockMvc(addFilters = false)
class ConsentControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ConsentService consentService;

    @BeforeEach
    void setUpAuth() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(1L, null, List.of())
        );
    }

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 내_동의_현황을_조회한다() throws Exception {
        when(consentService.getConsentStatus(1L)).thenReturn(
                ConsentStatusResponse.builder().consents(List.of(
                        ConsentStatusResponse.ConsentItem.builder()
                                .type("TERMS").agreed(true).version("1.0").build(),
                        ConsentStatusResponse.ConsentItem.builder()
                                .type("PRIVACY").agreed(true).version("1.0").build(),
                        ConsentStatusResponse.ConsentItem.builder()
                                .type("MARKETING").agreed(false).version("1.0").build()
                )).build()
        );

        mockMvc.perform(get("/users/me/consents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.consents[0].type").value("TERMS"))
                .andExpect(jsonPath("$.data.consents[2].type").value("MARKETING"))
                .andExpect(jsonPath("$.data.consents[2].agreed").value(false));
    }

    @Test
    void 마케팅_동의를_변경한다() throws Exception {
        when(consentService.updateMarketingConsent(1L, true)).thenReturn(
                ConsentStatusResponse.ConsentItem.builder()
                        .type("MARKETING").agreed(true).version("1.0").build()
        );

        mockMvc.perform(put("/users/me/consents/marketing")
                        .contentType("application/json")
                        .content("{\"agreed\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.type").value("MARKETING"))
                .andExpect(jsonPath("$.data.agreed").value(true));
    }

    @Test
    void 마케팅_동의_변경시_agreed가_없으면_400을_반환한다() throws Exception {
        mockMvc.perform(put("/users/me/consents/marketing")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }
}
```

- [ ] **Step 3: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests "com.example.demo.user.controller.ConsentControllerTest"`
Expected: FAIL (컴파일 에러 — `ConsentController` 클래스가 아직 없음)

- [ ] **Step 4: ConsentController 구현**

`src/main/java/com/example/demo/user/controller/ConsentController.java`:

```java
package com.example.demo.user.controller;

import com.example.demo.global.response.ApiResponse;
import com.example.demo.global.response.SuccessCode;
import com.example.demo.user.dto.ConsentStatusResponse;
import com.example.demo.user.dto.MarketingConsentRequest;
import com.example.demo.user.service.ConsentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/users/me/consents")
@RequiredArgsConstructor
public class ConsentController {

    private final ConsentService consentService;

    @GetMapping
    public ApiResponse<ConsentStatusResponse> getMyConsents(@AuthenticationPrincipal Long userId) {
        return ApiResponse.success(
                SuccessCode.CONSENT_STATUS_FOUND,
                consentService.getConsentStatus(userId)
        );
    }

    @PutMapping("/marketing")
    public ApiResponse<ConsentStatusResponse.ConsentItem> updateMarketingConsent(
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody MarketingConsentRequest request
    ) {
        return ApiResponse.success(
                SuccessCode.MARKETING_CONSENT_UPDATED,
                consentService.updateMarketingConsent(userId, request.getAgreed())
        );
    }
}
```

- [ ] **Step 5: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests "com.example.demo.user.controller.ConsentControllerTest"`
Expected: `BUILD SUCCESSFUL`, 3 tests passed

- [ ] **Step 6: Commit**

```bash
git add src/main/java/com/example/demo/user/dto/MarketingConsentRequest.java src/main/java/com/example/demo/user/controller/ConsentController.java src/test/java/com/example/demo/user/controller/ConsentControllerTest.java
git commit -m "feat: 동의 현황 조회 및 마케팅 동의 변경 API 추가"
```

---

## Task 7: 전체 검증 + API 레퍼런스 문서 갱신

**Files:**
- Modify: `docs/API_REFERENCE.md`

**Interfaces:**
- Consumes: 없음 (검증 + 문서만).

- [ ] **Step 1: 전체 테스트 스위트 실행**

Run: `./gradlew test`
Expected: 신규 포함 전체 테스트 통과. 유일하게 허용되는 실패는 사전에 존재하던 `DemoApplicationTests.contextLoads()` (환경변수 `VWORLD_API_KEY` 미설정, 이 Phase와 무관).

- [ ] **Step 2: 제목/회원가입 섹션 갱신**

`docs/API_REFERENCE.md` 1번째 줄:

```markdown
# 로그인/회원가입 API 레퍼런스 (Phase 1 + 2 + 3 + 4)
```

4번(회원가입) 섹션의 요청 JSON과 실패 목록을 아래로 교체:

````markdown
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
````

- [ ] **Step 3: 신규 API 3개 문서 추가**

14번(구글 로그인) 섹션과 `## 에러 코드 전체 목록` 사이에 추가:

````markdown

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
````

- [ ] **Step 4: 구글 로그인 섹션에 동의 간주 안내 추가**

14번(구글 로그인) 섹션의 불릿 목록 마지막에 추가:

```markdown
- **동의 처리**: 구글 로그인으로 신규 가입되는 경우 필수 약관(이용약관/개인정보 수집·이용)에 동의한 것으로 처리되고 마케팅 수신은 미동의로 기록됩니다. 프론트는 구글 로그인 버튼 근처에 "구글 로그인 시 이용약관 및 개인정보처리방침에 동의한 것으로 간주됩니다" 문구를 표시해주세요 (문구 안의 각 약관명은 15번 API 본문을 보여주는 링크로 처리 권장).
```

- [ ] **Step 5: 에러 코드 표에 추가**

`## 에러 코드 전체 목록` 표의 마지막 행 다음에 추가:

```markdown
| 약관 타입 없음 | 404 | 약관을 찾을 수 없습니다. |
| 필수 약관 미동의 (회원가입) | 400 | `data`에 `{termsAgreed/privacyAgreed: "필수 약관에 동의해야 합니다."}` |
```

- [ ] **Step 6: Commit**

```bash
git add docs/API_REFERENCE.md
git commit -m "docs: 개인정보 동의/약관 API 문서 추가"
```
