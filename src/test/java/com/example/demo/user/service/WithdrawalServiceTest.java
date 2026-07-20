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
