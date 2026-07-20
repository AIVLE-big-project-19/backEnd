package com.example.demo.user.service;

import com.example.demo.board.entity.Board;
import com.example.demo.board.repository.BoardRepository;
import com.example.demo.comment.entity.Comment;
import com.example.demo.comment.repository.CommentRepository;
import com.example.demo.global.config.JpaAuditingConfig;
import com.example.demo.user.entity.ConsentType;
import com.example.demo.user.entity.Provider;
import com.example.demo.user.entity.RefreshToken;
import com.example.demo.user.entity.Role;
import com.example.demo.user.entity.User;
import com.example.demo.user.entity.UserConsent;
import com.example.demo.user.repository.RefreshTokenRepository;
import com.example.demo.user.repository.UserConsentRepository;
import com.example.demo.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;

/**
 * WithdrawalService의 핵심 안전 불변식(FK가 걸린 refresh_tokens/user_consents가
 * users 행보다 먼저 삭제되어야 한다)을 Mockito가 아니라 실제 H2 DB와 실제
 * 리포지토리 구현으로 증명한다. WithdrawalServiceTest(Mockito InOrder)는 mock 호출
 * 순서만 검증하므로 실제 FK 제약 위반 여부는 증명하지 못한다 — 이 테스트가 그 공백을 메운다.
 */
@DataJpaTest(properties = {
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never"
})
@Import(JpaAuditingConfig.class)
class WithdrawalServiceIntegrationTest {

    private static final String ANONYMIZED_WRITER = "탈퇴한 사용자";
    private static final String RAW_PASSWORD = "password1!";

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CommentRepository commentRepository;

    @Autowired
    private BoardRepository boardRepository;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private UserConsentRepository userConsentRepository;

    private WithdrawalService withdrawalService;

    @BeforeEach
    void setUp() {
        withdrawalService = new WithdrawalService(
                userRepository,
                commentRepository,
                boardRepository,
                refreshTokenRepository,
                userConsentRepository,
                new BCryptPasswordEncoder(),
                mock(LoginAttemptService.class),
                mock(EmailVerificationService.class)
        );
    }

    @Test
    void 탈퇴시_refreshToken과_동의이력이_유저보다_먼저_삭제되고_게시글_댓글이_익명화된다() {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

        User withdrawing = userRepository.save(User.builder()
                .loginId("withdrawer01")
                .email("withdrawer01@example.com")
                .password(encoder.encode(RAW_PASSWORD))
                .name("탈퇴예정자")
                .provider(Provider.LOCAL)
                .role(Role.USER)
                .build());

        User other = userRepository.save(User.builder()
                .loginId("other99")
                .email("other99@example.com")
                .password(encoder.encode("otherpw1!"))
                .name("다른사용자")
                .provider(Provider.LOCAL)
                .role(Role.USER)
                .build());

        refreshTokenRepository.save(RefreshToken.builder()
                .user(withdrawing)
                .tokenHash("hash-withdrawer")
                .expiresAt(LocalDateTime.now().plusDays(7))
                .rememberMe(false)
                .build());

        userConsentRepository.save(UserConsent.builder()
                .user(withdrawing)
                .consentType(ConsentType.TERMS)
                .agreed(true)
                .termsVersion("1.0")
                .build());

        Board myBoard = boardRepository.save(Board.builder()
                .title("내 글")
                .content("내용")
                .writer("withdrawer01")
                .category("자유게시판")
                .build());

        Comment myComment = commentRepository.save(Comment.builder()
                .board(myBoard)
                .writer("withdrawer01")
                .author(withdrawing)
                .content("내 댓글")
                .build());

        Board otherBoard = boardRepository.save(Board.builder()
                .title("남의 글")
                .content("내용")
                .writer("other99")
                .category("자유게시판")
                .build());

        Comment otherComment = commentRepository.save(Comment.builder()
                .board(otherBoard)
                .writer("other99")
                .author(other)
                .content("남의 댓글")
                .build());

        assertThatCode(() -> withdrawalService.withdraw(withdrawing.getId(), RAW_PASSWORD))
                .doesNotThrowAnyException();

        assertThat(userRepository.findById(withdrawing.getId())).isEmpty();

        assertThat(refreshTokenRepository.findAll())
                .noneMatch(rt -> rt.getUser().getId().equals(withdrawing.getId()));
        assertThat(userConsentRepository.findAll())
                .noneMatch(uc -> uc.getUser().getId().equals(withdrawing.getId()));

        Comment reloadedMyComment = commentRepository.findById(myComment.getCommentId()).orElseThrow();
        assertThat(reloadedMyComment.getWriter()).isEqualTo(ANONYMIZED_WRITER);
        assertThat(reloadedMyComment.getAuthor()).isNull();

        Board reloadedMyBoard = boardRepository.findById(myBoard.getBoardId()).orElseThrow();
        assertThat(reloadedMyBoard.getWriter()).isEqualTo(ANONYMIZED_WRITER);

        Comment reloadedOtherComment = commentRepository.findById(otherComment.getCommentId()).orElseThrow();
        assertThat(reloadedOtherComment.getWriter()).isEqualTo("other99");
        assertThat(reloadedOtherComment.getAuthor().getId()).isEqualTo(other.getId());

        Board reloadedOtherBoard = boardRepository.findById(otherBoard.getBoardId()).orElseThrow();
        assertThat(reloadedOtherBoard.getWriter()).isEqualTo("other99");

        assertThat(userRepository.findById(other.getId())).isPresent();
    }
}
