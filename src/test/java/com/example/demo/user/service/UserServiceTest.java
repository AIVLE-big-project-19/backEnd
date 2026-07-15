package com.example.demo.user.service;

import com.example.demo.global.exception.CustomException;
import com.example.demo.global.exception.ErrorCode;
import com.example.demo.user.dto.FindIdResponse;
import com.example.demo.user.dto.SignupRequest;
import com.example.demo.user.entity.Provider;
import com.example.demo.user.entity.Role;
import com.example.demo.user.entity.User;
import com.example.demo.user.repository.RefreshTokenRepository;
import com.example.demo.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class UserServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private EmailVerificationService emailVerificationService;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    private UserService userService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        userService = new UserService(userRepository, emailVerificationService, passwordEncoder, refreshTokenRepository);
    }

    @Test
    void 사용_가능한_아이디면_true를_반환한다() {
        when(userRepository.existsByLoginId("newid")).thenReturn(false);

        assertThat(userService.checkLoginIdAvailable("newid")).isTrue();
    }

    @Test
    void 이메일_인증이_안됐으면_회원가입시_예외가_발생한다() {
        SignupRequest request = validRequest();
        when(emailVerificationService.isVerified(request.getEmail())).thenReturn(false);

        assertThatThrownBy(() -> userService.signup(request))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.EMAIL_VERIFICATION_REQUIRED);
    }

    @Test
    void 이미_사용중인_아이디면_예외가_발생한다() {
        SignupRequest request = validRequest();
        when(emailVerificationService.isVerified(request.getEmail())).thenReturn(true);
        when(userRepository.existsByLoginId(request.getLoginId())).thenReturn(true);

        assertThatThrownBy(() -> userService.signup(request))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.DUPLICATE_LOGIN_ID);
    }

    @Test
    void 이미_가입된_이메일이면_예외가_발생한다() {
        SignupRequest request = validRequest();
        when(emailVerificationService.isVerified(request.getEmail())).thenReturn(true);
        when(userRepository.existsByLoginId(request.getLoginId())).thenReturn(false);
        when(userRepository.existsByEmail(request.getEmail())).thenReturn(true);

        assertThatThrownBy(() -> userService.signup(request))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.DUPLICATE_EMAIL);
    }

    @Test
    void 정상_요청이면_비밀번호를_인코딩해서_저장하고_인증플래그를_지운다() {
        SignupRequest request = validRequest();
        when(emailVerificationService.isVerified(request.getEmail())).thenReturn(true);
        when(userRepository.existsByLoginId(request.getLoginId())).thenReturn(false);
        when(userRepository.existsByEmail(request.getEmail())).thenReturn(false);
        when(passwordEncoder.encode(request.getPassword())).thenReturn("ENCODED");

        userService.signup(request);

        ArgumentCaptor<User> captor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(captor.capture());
        User saved = captor.getValue();

        assertThat(saved.getPassword()).isEqualTo("ENCODED");
        assertThat(saved.getProvider()).isEqualTo(Provider.LOCAL);
        assertThat(saved.getRole()).isEqualTo(Role.USER);
        verify(emailVerificationService).clearVerified(request.getEmail());
    }

    private SignupRequest validRequest() {
        SignupRequest request = new SignupRequest();
        request.setLoginId("tester01");
        request.setEmail("tester01@example.com");
        request.setPassword("password123");
        request.setName("테스터");
        return request;
    }

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

        verify(emailVerificationService).sendCode("tester01@example.com", "아이디 찾기");
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
    void 구글계정_이메일이면_아이디찾기_인증코드확인_시_예외가_발생한다() {
        User googleUser = User.builder()
                .loginId(null)
                .email("google-user@example.com")
                .provider(Provider.GOOGLE)
                .role(Role.USER)
                .build();
        when(userRepository.findByEmail("google-user@example.com")).thenReturn(Optional.of(googleUser));

        assertThatThrownBy(() -> userService.findIdVerifyCode("google-user@example.com", "123456"))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.USER_NOT_FOUND);

        verify(emailVerificationService, never()).setIdentityVerified(anyString());
    }

    @Test
    void 인증코드가_일치하면_마스킹된_아이디와_가입일을_반환한다() {
        LocalDateTime createdAt = LocalDateTime.of(2026, 1, 1, 12, 0);
        User user = mock(User.class);
        when(user.getLoginId()).thenReturn("tester01");
        when(user.getCreatedAt()).thenReturn(createdAt);
        when(userRepository.findByEmail("tester01@example.com")).thenReturn(Optional.of(user));

        FindIdResponse response = userService.findIdVerifyCode("tester01@example.com", "123456");

        assertThat(response.getLoginId()).isEqualTo("tester01");
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

        verify(emailVerificationService).sendCode("tester01@example.com", "비밀번호 찾기");
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

    @Test
    void 인증되지_않았으면_비밀번호_재설정_시_예외가_발생한다() {
        when(emailVerificationService.isIdentityVerified("tester01")).thenReturn(false);

        assertThatThrownBy(() -> userService.resetPassword("tester01", "newPassword123"))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.IDENTITY_NOT_VERIFIED);

        verify(userRepository, never()).save(any());
        verify(refreshTokenRepository, never()).deleteByUser(any());
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
        verify(refreshTokenRepository).deleteByUser(user);
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
        verify(refreshTokenRepository, never()).deleteByUser(any());
        verify(emailVerificationService, never()).clearIdentityVerified(anyString());
    }
}
