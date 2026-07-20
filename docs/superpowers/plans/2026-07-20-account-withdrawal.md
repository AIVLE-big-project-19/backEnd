# 회원탈퇴 (Phase 6) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `POST /users/me/withdrawal`로 본인확인(LOCAL만) → 게시글/댓글 익명화 → 토큰·동의이력·유저 완전 삭제 → Redis 정리를 단일 트랜잭션으로 수행하는 회원탈퇴를 구현한다.

**Architecture:** 신규 `WithdrawalService`가 6개 저장소/서비스를 조율한다: 댓글은 `author` FK 해제 + 표시명 "탈퇴한 사용자"로 벌크 익명화, 게시글은 `writer` 문자열(loginId) 교체(Board는 FK가 없음), refreshToken/동의이력은 FK 제약상 유저 행보다 먼저 삭제, 마지막으로 Redis의 로그인 잠금 키와 이메일 인증완료 키를 정리한다(후자는 탈퇴 직후 재가입 시 이메일 인증 우회 방지). 상세 설계: `docs/superpowers/specs/2026-07-20-account-withdrawal-design.md`.

**Tech Stack:** Spring Boot 4.1.0(기존 유지), Spring Data JPA(`@Modifying` 벌크 업데이트), JUnit 5 + Mockito(단위), `@DataJpaTest`+H2(벌크 쿼리 검증), MockMvc(`@WebMvcTest`).

## Global Constraints

- 익명화 표시 문자열은 **"탈퇴한 사용자"** — `WithdrawalService`의 상수 `ANONYMIZED_WRITER`로 정의. (`comment.writer`/`board.writer` 컬럼 length 30 이내)
- 신규 ErrorCode 없음 — 비밀번호 불일치/미전송은 기존 `INVALID_CREDENTIALS`(401), 유저 없음은 기존 `USER_NOT_FOUND`(404) 재사용.
- 본인확인: `Provider.LOCAL`만 비밀번호 검증. `GOOGLE`은 검증 생략, board 익명화(loginId 없음)와 `clearLockState`(loginId 기준 키)도 스킵.
- 삭제 순서 필수: 댓글/게시글 익명화 → refreshToken 삭제 → 동의이력 삭제 → 유저 행 삭제 → Redis 정리. FK(`refresh_tokens.user_id`, `user_consents.user_id` 모두 nullable=false) 때문에 유저 행 삭제가 마지막 DB 작업이어야 한다.
- `@Modifying` 벌크 업데이트는 `clearAutomatically = true`를 붙인다 (같은 트랜잭션 내 영속성 컨텍스트 stale 방지).
- 컨트롤러는 기존 `MyPageController`(`/users/me` 프리픽스)에 추가, 인증은 `@AuthenticationPrincipal Long userId` 패턴.
- **이 프로젝트는 Spring Boot 4.1.0 / Spring Framework 7 / Jackson 3 기준이다.** `@WebMvcTest`/`@AutoConfigureMockMvc`는 `org.springframework.boot.webmvc.test.autoconfigure.*`, `@DataJpaTest`는 `org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest`, `@MockBean` 대신 `org.springframework.test.context.bean.override.mockito.MockitoBean`, `ObjectMapper`는 `tools.jackson.databind.ObjectMapper`. Spring Boot 3.x 예제의 import 경로를 베끼지 말 것.

---

## Task 1: SuccessCode + 리포지토리 벌크 메서드 (H2 검증 포함)

**Files:**
- Modify: `src/main/java/com/example/demo/global/response/SuccessCode.java`
- Modify: `src/main/java/com/example/demo/comment/repository/CommentRepository.java`
- Modify: `src/main/java/com/example/demo/board/repository/BoardRepository.java`
- Modify: `src/main/java/com/example/demo/user/repository/UserConsentRepository.java`
- Test: `src/test/java/com/example/demo/comment/repository/CommentRepositoryTest.java`
- Test: `src/test/java/com/example/demo/board/repository/BoardRepositoryTest.java`

**Interfaces:**
- Produces: `SuccessCode.USER_WITHDRAWN`, `CommentRepository.anonymizeByAuthor(User author, String writer): int`, `BoardRepository.replaceWriter(String writer, String newWriter): int`, `UserConsentRepository.deleteByUser(User user): void`. Task 2가 그대로 호출한다.

- [ ] **Step 1: SuccessCode에 USER_WITHDRAWN 추가**

`SuccessCode.java`의 `MARKETING_CONSENT_UPDATED("마케팅 수신 동의가 변경되었습니다."),` 다음 줄에 추가:

```java
    MARKETING_CONSENT_UPDATED("마케팅 수신 동의가 변경되었습니다."),
    USER_WITHDRAWN("회원 탈퇴가 완료되었습니다."),
```

- [ ] **Step 2: 실패하는 H2 슬라이스 테스트 작성 (댓글 익명화)**

`src/test/java/com/example/demo/comment/repository/CommentRepositoryTest.java`:

```java
package com.example.demo.comment.repository;

import com.example.demo.board.entity.Board;
import com.example.demo.board.repository.BoardRepository;
import com.example.demo.comment.entity.Comment;
import com.example.demo.global.config.JpaAuditingConfig;
import com.example.demo.user.entity.Provider;
import com.example.demo.user.entity.Role;
import com.example.demo.user.entity.User;
import com.example.demo.user.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never"
})
@Import(JpaAuditingConfig.class)
class CommentRepositoryTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private BoardRepository boardRepository;

    @Autowired
    private CommentRepository commentRepository;

    @Test
    void 익명화하면_해당_작성자의_댓글만_FK가_끊기고_표시명이_교체된다() {
        User author = userRepository.save(User.builder()
                .loginId("tester01")
                .email("withdraw-comment@test.com")
                .name("테스터")
                .provider(Provider.LOCAL)
                .role(Role.USER)
                .build());

        Board board = boardRepository.save(Board.builder()
                .title("제목")
                .content("내용")
                .writer("tester01")
                .category("자유게시판")
                .build());

        commentRepository.save(Comment.builder()
                .board(board).writer("tester01").author(author).content("내 댓글 1").build());
        commentRepository.save(Comment.builder()
                .board(board).writer("tester01").author(author).content("내 댓글 2").build());
        commentRepository.save(Comment.builder()
                .board(board).writer("other99").content("남의 댓글").build());

        int updated = commentRepository.anonymizeByAuthor(author, "탈퇴한 사용자");

        assertThat(updated).isEqualTo(2);

        List<Comment> all = commentRepository.findAll();
        assertThat(all).filteredOn(c -> c.getWriter().equals("탈퇴한 사용자"))
                .hasSize(2)
                .allSatisfy(c -> assertThat(c.getAuthor()).isNull());
        assertThat(all).filteredOn(c -> c.getWriter().equals("other99")).hasSize(1);
    }
}
```

- [ ] **Step 3: 실패하는 H2 슬라이스 테스트 작성 (게시글 writer 교체)**

`src/test/java/com/example/demo/board/repository/BoardRepositoryTest.java`:

```java
package com.example.demo.board.repository;

import com.example.demo.board.entity.Board;
import com.example.demo.global.config.JpaAuditingConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest(properties = {
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never"
})
@Import(JpaAuditingConfig.class)
class BoardRepositoryTest {

    @Autowired
    private BoardRepository boardRepository;

    private Board newBoard(String writer, String title) {
        return boardRepository.save(Board.builder()
                .title(title)
                .content("내용")
                .writer(writer)
                .category("자유게시판")
                .build());
    }

    @Test
    void writer_교체시_해당_작성자의_게시글만_바뀐다() {
        newBoard("tester01", "내 글 1");
        newBoard("tester01", "내 글 2");
        newBoard("other99", "남의 글");

        int updated = boardRepository.replaceWriter("tester01", "탈퇴한 사용자");

        assertThat(updated).isEqualTo(2);

        List<Board> all = boardRepository.findAll();
        assertThat(all).filteredOn(b -> b.getWriter().equals("탈퇴한 사용자")).hasSize(2);
        assertThat(all).filteredOn(b -> b.getWriter().equals("other99")).hasSize(1);
    }
}
```

- [ ] **Step 4: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests "com.example.demo.comment.repository.CommentRepositoryTest" --tests "com.example.demo.board.repository.BoardRepositoryTest"`
Expected: FAIL (컴파일 에러 — `anonymizeByAuthor`/`replaceWriter` 메서드가 아직 없음)

- [ ] **Step 5: 리포지토리 메서드 구현**

`CommentRepository.java` 전체를 아래로 교체:

```java
package com.example.demo.comment.repository;

import com.example.demo.board.entity.Board;
import com.example.demo.comment.entity.Comment;
import com.example.demo.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CommentRepository extends JpaRepository<Comment, Long> {

    /**
     * 게시글의 댓글 전체 조회
     */
    List<Comment> findByBoard(Board board);

    /**
     * 생성일 기준 오름차순 조회
     */
    List<Comment> findByBoardOrderByCreatedAtAsc(Board board);

    /**
     * 회원탈퇴 시 작성자 익명화: author FK를 끊고 표시명을 교체한다.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Comment c SET c.writer = :writer, c.author = null WHERE c.author = :author")
    int anonymizeByAuthor(@Param("author") User author, @Param("writer") String writer);
}
```

`BoardRepository.java`의 `findByWriterOrderByCreatedAtDesc` 다음에 추가 (import 3개도 추가: `org.springframework.data.jpa.repository.Modifying`, `org.springframework.data.jpa.repository.Query`, `org.springframework.data.repository.query.Param`):

```java
    List<Board> findByWriterOrderByCreatedAtDesc(String writer);

    /**
     * 회원탈퇴 시 작성자 표시명 교체 (Board는 User FK가 없어 loginId 문자열 기준).
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Board b SET b.writer = :newWriter WHERE b.writer = :writer")
    int replaceWriter(@Param("writer") String writer, @Param("newWriter") String newWriter);
```

`UserConsentRepository.java`의 `findTopByUserAndConsentTypeOrderByIdDesc(...)` 다음에 추가:

```java
    void deleteByUser(User user);
```

- [ ] **Step 6: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests "com.example.demo.comment.repository.CommentRepositoryTest" --tests "com.example.demo.board.repository.BoardRepositoryTest"`
Expected: `BUILD SUCCESSFUL`, 2 tests passed

- [ ] **Step 7: 컴파일 확인 (UserConsentRepository 포함)**

Run: `./gradlew compileJava compileTestJava`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/example/demo/global/response/SuccessCode.java src/main/java/com/example/demo/comment/repository/CommentRepository.java src/main/java/com/example/demo/board/repository/BoardRepository.java src/main/java/com/example/demo/user/repository/UserConsentRepository.java src/test/java/com/example/demo/comment/repository/CommentRepositoryTest.java src/test/java/com/example/demo/board/repository/BoardRepositoryTest.java
git commit -m "feat: 회원탈퇴용 익명화/삭제 리포지토리 메서드 추가"
```

---

## Task 2: WithdrawalService

**Files:**
- Create: `src/main/java/com/example/demo/user/service/WithdrawalService.java`
- Test: `src/test/java/com/example/demo/user/service/WithdrawalServiceTest.java`

**Interfaces:**
- Consumes: `CommentRepository.anonymizeByAuthor(User, String)`, `BoardRepository.replaceWriter(String, String)`, `UserConsentRepository.deleteByUser(User)` (Task 1), 기존 `RefreshTokenRepository.deleteByUser(User)`, `LoginAttemptService.clearLockState(String)`, `EmailVerificationService.clearVerified(String)`.
- Produces: `WithdrawalService.withdraw(Long userId, String password): void` — Task 3의 컨트롤러가 그대로 호출한다.

- [ ] **Step 1: 실패하는 테스트 작성**

`src/test/java/com/example/demo/user/service/WithdrawalServiceTest.java`:

```java
package com.example.demo.user.service;

import com.example.demo.board.repository.BoardRepository;
import com.example.demo.comment.repository.CommentRepository;
import com.example.demo.global.exception.CustomException;
import com.example.demo.global.exception.ErrorCode;
import com.example.demo.user.entity.Provider;
import com.example.demo.user.entity.Role;
import com.example.demo.user.entity.User;
import com.example.demo.user.repository.RefreshTokenRepository;
import com.example.demo.user.repository.UserConsentRepository;
import com.example.demo.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class WithdrawalServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private CommentRepository commentRepository;

    @Mock
    private BoardRepository boardRepository;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private UserConsentRepository userConsentRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private LoginAttemptService loginAttemptService;

    @Mock
    private EmailVerificationService emailVerificationService;

    private WithdrawalService withdrawalService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        withdrawalService = new WithdrawalService(
                userRepository, commentRepository, boardRepository,
                refreshTokenRepository, userConsentRepository,
                passwordEncoder, loginAttemptService, emailVerificationService
        );
    }

    private User localUser() {
        return User.builder()
                .id(1L)
                .loginId("tester01")
                .email("tester01@example.com")
                .password("ENCODED")
                .name("테스터")
                .provider(Provider.LOCAL)
                .role(Role.USER)
                .build();
    }

    private User googleUser() {
        return User.builder()
                .id(2L)
                .email("google@example.com")
                .name("구글사용자")
                .provider(Provider.GOOGLE)
                .providerId("google-sub-1")
                .role(Role.USER)
                .build();
    }

    @Test
    void LOCAL_비밀번호가_일치하면_익명화와_삭제를_순서대로_수행한다() {
        User user = localUser();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password1!", "ENCODED")).thenReturn(true);

        withdrawalService.withdraw(1L, "password1!");

        verify(commentRepository).anonymizeByAuthor(user, "탈퇴한 사용자");
        verify(boardRepository).replaceWriter("tester01", "탈퇴한 사용자");

        InOrder inOrder = inOrder(refreshTokenRepository, userConsentRepository, userRepository);
        inOrder.verify(refreshTokenRepository).deleteByUser(user);
        inOrder.verify(userConsentRepository).deleteByUser(user);
        inOrder.verify(userRepository).delete(user);

        verify(loginAttemptService).clearLockState("tester01");
        verify(emailVerificationService).clearVerified("tester01@example.com");
    }

    @Test
    void LOCAL_비밀번호가_틀리면_예외가_발생하고_아무것도_삭제되지_않는다() {
        User user = localUser();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "ENCODED")).thenReturn(false);

        assertThatThrownBy(() -> withdrawalService.withdraw(1L, "wrong"))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_CREDENTIALS);

        verify(commentRepository, never()).anonymizeByAuthor(any(), anyString());
        verify(userRepository, never()).delete(any(User.class));
    }

    @Test
    void LOCAL_비밀번호를_보내지_않으면_예외가_발생한다() {
        User user = localUser();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> withdrawalService.withdraw(1L, null))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_CREDENTIALS);

        verify(passwordEncoder, never()).matches(any(), any());
        verify(userRepository, never()).delete(any(User.class));
    }

    @Test
    void 구글_계정은_비밀번호_확인_없이_탈퇴하고_loginId_기반_처리는_스킵한다() {
        User user = googleUser();
        when(userRepository.findById(2L)).thenReturn(Optional.of(user));

        withdrawalService.withdraw(2L, null);

        verify(passwordEncoder, never()).matches(any(), any());
        verify(commentRepository).anonymizeByAuthor(user, "탈퇴한 사용자");
        verify(boardRepository, never()).replaceWriter(anyString(), anyString());
        verify(refreshTokenRepository).deleteByUser(user);
        verify(userConsentRepository).deleteByUser(user);
        verify(userRepository).delete(user);
        verify(loginAttemptService, never()).clearLockState(anyString());
        verify(emailVerificationService).clearVerified("google@example.com");
    }

    @Test
    void 없는_유저면_예외가_발생한다() {
        when(userRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> withdrawalService.withdraw(99L, "password1!"))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.USER_NOT_FOUND);
    }
}
```

- [ ] **Step 2: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests "com.example.demo.user.service.WithdrawalServiceTest"`
Expected: FAIL (컴파일 에러 — `WithdrawalService` 클래스가 아직 없음)

- [ ] **Step 3: WithdrawalService 구현**

`src/main/java/com/example/demo/user/service/WithdrawalService.java`:

```java
package com.example.demo.user.service;

import com.example.demo.board.repository.BoardRepository;
import com.example.demo.comment.repository.CommentRepository;
import com.example.demo.global.exception.CustomException;
import com.example.demo.global.exception.ErrorCode;
import com.example.demo.user.entity.Provider;
import com.example.demo.user.entity.User;
import com.example.demo.user.repository.RefreshTokenRepository;
import com.example.demo.user.repository.UserConsentRepository;
import com.example.demo.user.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WithdrawalService {

    static final String ANONYMIZED_WRITER = "탈퇴한 사용자";

    private final UserRepository userRepository;
    private final CommentRepository commentRepository;
    private final BoardRepository boardRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final UserConsentRepository userConsentRepository;
    private final PasswordEncoder passwordEncoder;
    private final LoginAttemptService loginAttemptService;
    private final EmailVerificationService emailVerificationService;

    public WithdrawalService(
            UserRepository userRepository,
            CommentRepository commentRepository,
            BoardRepository boardRepository,
            RefreshTokenRepository refreshTokenRepository,
            UserConsentRepository userConsentRepository,
            PasswordEncoder passwordEncoder,
            LoginAttemptService loginAttemptService,
            EmailVerificationService emailVerificationService
    ) {
        this.userRepository = userRepository;
        this.commentRepository = commentRepository;
        this.boardRepository = boardRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.userConsentRepository = userConsentRepository;
        this.passwordEncoder = passwordEncoder;
        this.loginAttemptService = loginAttemptService;
        this.emailVerificationService = emailVerificationService;
    }

    @Transactional
    public void withdraw(Long userId, String password) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        verifyIdentity(user, password);

        commentRepository.anonymizeByAuthor(user, ANONYMIZED_WRITER);

        String loginId = user.getLoginId();
        if (loginId != null) {
            boardRepository.replaceWriter(loginId, ANONYMIZED_WRITER);
        }

        refreshTokenRepository.deleteByUser(user);
        userConsentRepository.deleteByUser(user);

        String email = user.getEmail();
        userRepository.delete(user);

        if (loginId != null) {
            loginAttemptService.clearLockState(loginId);
        }
        emailVerificationService.clearVerified(email);
    }

    private void verifyIdentity(User user, String password) {
        if (user.getProvider() != Provider.LOCAL) {
            return;
        }

        if (password == null || !passwordEncoder.matches(password, user.getPassword())) {
            throw new CustomException(ErrorCode.INVALID_CREDENTIALS);
        }
    }
}
```

- [ ] **Step 4: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests "com.example.demo.user.service.WithdrawalServiceTest"`
Expected: `BUILD SUCCESSFUL`, 5 tests passed

- [ ] **Step 5: Commit**

```bash
git add src/main/java/com/example/demo/user/service/WithdrawalService.java src/test/java/com/example/demo/user/service/WithdrawalServiceTest.java
git commit -m "feat: WithdrawalService로 회원탈퇴(익명화+완전삭제+Redis정리) 구현"
```

---

## Task 3: 탈퇴 엔드포인트 + API 문서 + 전체 검증

**Files:**
- Create: `src/main/java/com/example/demo/user/dto/WithdrawalRequest.java`
- Modify: `src/main/java/com/example/demo/user/controller/MyPageController.java`
- Test: `src/test/java/com/example/demo/user/controller/MyPageControllerTest.java` (신규 파일)
- Modify: `docs/API_REFERENCE.md`

**Interfaces:**
- Consumes: `WithdrawalService.withdraw(Long, String)` (Task 2), `SuccessCode.USER_WITHDRAWN` (Task 1).
- Produces: `POST /users/me/withdrawal` 엔드포인트.

- [ ] **Step 1: WithdrawalRequest DTO 작성**

`src/main/java/com/example/demo/user/dto/WithdrawalRequest.java`:

```java
package com.example.demo.user.dto;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class WithdrawalRequest {

    // LOCAL 계정은 필수, 구글 계정은 미전송 허용 — 검증은 WithdrawalService에서 수행
    private String password;
}
```

- [ ] **Step 2: 실패하는 컨트롤러 테스트 작성**

`src/test/java/com/example/demo/user/controller/MyPageControllerTest.java` (신규 파일 — 기존에 MyPageController 테스트 없음):

```java
package com.example.demo.user.controller;

import com.example.demo.global.exception.CustomException;
import com.example.demo.global.exception.ErrorCode;
import com.example.demo.user.service.MyPageService;
import com.example.demo.user.service.WithdrawalService;
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

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MyPageController.class)
@AutoConfigureMockMvc(addFilters = false)
class MyPageControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MyPageService myPageService;

    @MockitoBean
    private WithdrawalService withdrawalService;

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
    void 탈퇴_성공시_200을_반환한다() throws Exception {
        mockMvc.perform(post("/users/me/withdrawal")
                        .contentType("application/json")
                        .content("{\"password\":\"password1!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("회원 탈퇴가 완료되었습니다."));

        verify(withdrawalService).withdraw(1L, "password1!");
    }

    @Test
    void 비밀번호가_틀리면_401을_반환한다() throws Exception {
        doThrow(new CustomException(ErrorCode.INVALID_CREDENTIALS))
                .when(withdrawalService).withdraw(1L, "wrong");

        mockMvc.perform(post("/users/me/withdrawal")
                        .contentType("application/json")
                        .content("{\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 구글_계정은_password_없이도_탈퇴_요청이_가능하다() throws Exception {
        mockMvc.perform(post("/users/me/withdrawal")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isOk());

        verify(withdrawalService).withdraw(1L, null);
    }
}
```

- [ ] **Step 3: 테스트 실행해서 실패 확인**

Run: `./gradlew test --tests "com.example.demo.user.controller.MyPageControllerTest"`
Expected: FAIL (`POST /users/me/withdrawal` 엔드포인트가 없어 404, `WithdrawalService` 의존성 없음으로 컴파일 에러일 수도 있음 — 어느 쪽이든 실패가 정상)

- [ ] **Step 4: MyPageController에 엔드포인트 추가**

`MyPageController.java` import에 추가:

```java
import com.example.demo.user.dto.WithdrawalRequest;
import com.example.demo.user.service.WithdrawalService;
```

필드 추가 (`private final MyPageService myPageService;` 아래 — `@RequiredArgsConstructor`가 생성자를 만들어주므로 필드만 추가):

```java
    private final WithdrawalService withdrawalService;
```

클래스 마지막 메서드(`getMyBoards`) 다음에 추가:

```java
    @PostMapping("/withdrawal")
    public ApiResponse<Void> withdraw(
            @AuthenticationPrincipal Long userId,
            @RequestBody(required = false) WithdrawalRequest request
    ) {
        String password = (request == null) ? null : request.getPassword();
        withdrawalService.withdraw(userId, password);
        return ApiResponse.success(SuccessCode.USER_WITHDRAWN);
    }
```

(`@PostMapping`/`@RequestBody`는 이미 쓰는 `org.springframework.web.bind.annotation.*` 와일드카드 import로 커버됨.)

- [ ] **Step 5: 테스트 실행해서 통과 확인**

Run: `./gradlew test --tests "com.example.demo.user.controller.MyPageControllerTest"`
Expected: `BUILD SUCCESSFUL`, 3 tests passed

- [ ] **Step 6: 전체 테스트 스위트 실행**

Run: `./gradlew test`
Expected: 전체 통과. 유일하게 허용되는 실패는 사전에 존재하던 `DemoApplicationTests.contextLoads()` (환경변수 `VWORLD_API_KEY` 미설정, 이 Phase와 무관).

- [ ] **Step 7: API_REFERENCE.md에 18번 엔드포인트 추가**

`docs/API_REFERENCE.md`의 17번(마케팅 수신 동의 변경) 섹션과 `## 에러 코드 전체 목록` 사이에 추가:

````markdown

### 18. 회원탈퇴
`POST /users/me/withdrawal` — `Authorization: Bearer {accessToken}` 필요
```json
{ "password": "현재비밀번호" }
```
- **일반(LOCAL) 계정**: `password` 필수 — 현재 비밀번호가 일치해야 탈퇴됩니다. 불일치/미전송 시 401.
- **구글 계정**: `password` 없이 빈 body(`{}`)로 호출 (비밀번호가 없는 계정이므로 확인 생략 — 프론트에서 "정말 탈퇴하시겠습니까?" 확인 모달 권장).
- 성공: 200 `"회원 탈퇴가 완료되었습니다."` — **모든 개인정보(계정·동의이력·로그인토큰)가 즉시 완전 삭제**되고 복구할 수 없습니다. 작성한 게시글/댓글은 삭제되지 않고 작성자가 "탈퇴한 사용자"로 표시됩니다.
- 성공 시 프론트: 저장된 accessToken/refreshToken 전부 삭제 후 홈 화면으로 이동해주세요.
- 실패: `INVALID_CREDENTIALS`(401, LOCAL 비밀번호 불일치), `USER_NOT_FOUND`(404)
- 탈퇴 후 같은 이메일로 재가입 가능하며, 이전 활동과 연결되지 않는 완전한 신규 회원이 됩니다.
````

- [ ] **Step 8: Commit**

```bash
git add src/main/java/com/example/demo/user/dto/WithdrawalRequest.java src/main/java/com/example/demo/user/controller/MyPageController.java src/test/java/com/example/demo/user/controller/MyPageControllerTest.java docs/API_REFERENCE.md
git commit -m "feat: POST /users/me/withdrawal 회원탈퇴 엔드포인트 추가"
```
