package com.example.demo.user.service;

import com.example.demo.global.exception.CustomException;
import com.example.demo.global.exception.ErrorCode;
import com.example.demo.global.security.jwt.JwtProvider;
import com.example.demo.global.util.HashUtil;
import com.example.demo.user.dto.LoginRequest;
import com.example.demo.user.dto.TokenResponse;
import com.example.demo.user.entity.Provider;
import com.example.demo.user.entity.Role;
import com.example.demo.user.entity.RefreshToken;
import com.example.demo.user.entity.User;
import com.example.demo.user.oauth.GoogleOAuthClient;
import com.example.demo.user.oauth.GoogleUserInfo;
import com.example.demo.user.repository.RefreshTokenRepository;
import com.example.demo.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class AuthServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private RefreshTokenRepository refreshTokenRepository;

    @Mock
    private JwtProvider jwtProvider;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private GoogleOAuthClient googleOAuthClient;

    @Mock
    private ConsentService consentService;

    @Mock
    private LoginAttemptService loginAttemptService;

    private AuthService authService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        authService = new AuthService(userRepository, refreshTokenRepository, jwtProvider, passwordEncoder, googleOAuthClient, consentService, loginAttemptService);
    }

    private User sampleUser() {
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

    @Test
    void 존재하지_않는_아이디면_예외가_발생한다() {
        LoginRequest request = new LoginRequest();
        request.setLoginId("nouser");
        request.setPassword("password123");

        when(userRepository.findByLoginId("nouser")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_CREDENTIALS);

        verify(loginAttemptService, never()).recordFailure(anyString());
    }

    @Test
    void 비밀번호가_틀리면_예외가_발생한다() {
        LoginRequest request = new LoginRequest();
        request.setLoginId("tester01");
        request.setPassword("wrong-password");

        when(userRepository.findByLoginId("tester01")).thenReturn(Optional.of(sampleUser()));
        when(passwordEncoder.matches("wrong-password", "ENCODED")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_CREDENTIALS);

        verify(loginAttemptService).recordFailure("tester01");
    }

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

    @Test
    void 로그인에_성공하면_토큰을_발급하고_refreshToken을_저장한다() {
        LoginRequest request = new LoginRequest();
        request.setLoginId("tester01");
        request.setPassword("password123");
        request.setRememberMe(true);

        when(userRepository.findByLoginId("tester01")).thenReturn(Optional.of(sampleUser()));
        when(passwordEncoder.matches("password123", "ENCODED")).thenReturn(true);
        when(jwtProvider.generateAccessToken(1L, Role.USER)).thenReturn("access-token");
        when(jwtProvider.generateRefreshToken(1L)).thenReturn("refresh-token");
        when(jwtProvider.getRefreshTokenValidityMs(true)).thenReturn(1_209_600_000L);

        TokenResponse response = authService.login(request);

        assertThat(response.getAccessToken()).isEqualTo("access-token");
        assertThat(response.getRefreshToken()).isEqualTo("refresh-token");

        ArgumentCaptor<RefreshToken> captor = ArgumentCaptor.forClass(RefreshToken.class);
        verify(refreshTokenRepository).save(captor.capture());
        RefreshToken saved = captor.getValue();
        assertThat(saved.getTokenHash()).isEqualTo(HashUtil.sha256("refresh-token"));
        assertThat(saved.isRememberMe()).isTrue();

        verify(loginAttemptService).recordSuccess("tester01");
    }

    @Test
    void 로그인에_성공하면_해당_사용자의_만료된_refreshToken을_정리한다() {
        LoginRequest request = new LoginRequest();
        request.setLoginId("tester01");
        request.setPassword("password123");

        User user = sampleUser();
        when(userRepository.findByLoginId("tester01")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password123", "ENCODED")).thenReturn(true);
        when(jwtProvider.generateAccessToken(1L, Role.USER)).thenReturn("access-token");
        when(jwtProvider.generateRefreshToken(1L)).thenReturn("refresh-token");
        when(jwtProvider.getRefreshTokenValidityMs(false)).thenReturn(86_400_000L);

        authService.login(request);

        verify(refreshTokenRepository).deleteByUserAndExpiresAtBefore(eq(user), any(LocalDateTime.class));
    }

    @Test
    void refresh_토큰이_DB에_없으면_예외가_발생한다() {
        when(jwtProvider.validateToken("bad-token")).thenReturn(true);
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.refresh("bad-token"))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_REFRESH_TOKEN);
    }

    @Test
    void refresh_토큰이_만료됐으면_예외가_발생한다() {
        RefreshToken expired = RefreshToken.builder()
                .id(1L)
                .user(sampleUser())
                .tokenHash("hash")
                .expiresAt(LocalDateTime.now().minusDays(1))
                .build();

        when(jwtProvider.validateToken("expired-token")).thenReturn(true);
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> authService.refresh("expired-token"))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.INVALID_REFRESH_TOKEN);
    }

    @Test
    void refresh시_기존_rememberMe_값을_유지한다() {
        RefreshToken saved = RefreshToken.builder()
                .id(1L)
                .user(sampleUser())
                .tokenHash("hash")
                .expiresAt(LocalDateTime.now().plusDays(10))
                .rememberMe(true)
                .build();

        when(jwtProvider.validateToken("old-token")).thenReturn(true);
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(saved));
        when(jwtProvider.generateAccessToken(1L, Role.USER)).thenReturn("new-access");
        when(jwtProvider.generateRefreshToken(1L)).thenReturn("new-refresh");
        when(jwtProvider.getRefreshTokenValidityMs(true)).thenReturn(1_209_600_000L);

        authService.refresh("old-token");

        verify(jwtProvider).getRefreshTokenValidityMs(true);
    }

    @Test
    void logout하면_저장된_refreshToken을_삭제한다() {
        RefreshToken saved = RefreshToken.builder()
                .id(1L)
                .user(sampleUser())
                .tokenHash("hash")
                .expiresAt(LocalDateTime.now().plusDays(1))
                .build();

        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.of(saved));

        authService.logout("some-refresh-token");

        verify(refreshTokenRepository).delete(saved);
    }

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

        verify(consentService).recordSignupConsents(any(User.class), eq(false));
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
        verify(consentService, never()).recordSignupConsents(any(User.class), anyBoolean());
    }

    @Test
    void 동시_회원가입_경쟁에서_패배하면_재시도_가능한_예외가_발생한다() {
        GoogleUserInfo googleUserInfo = GoogleUserInfo.builder()
                .providerId("google-sub-4")
                .email("racer@example.com")
                .name("경쟁자")
                .build();

        when(googleOAuthClient.fetchUserInfo("auth-code", "http://localhost:5173/callback")).thenReturn(googleUserInfo);
        when(userRepository.findByEmail("racer@example.com")).thenReturn(Optional.empty());
        when(userRepository.save(any(User.class))).thenThrow(new DataIntegrityViolationException("duplicate email"));

        assertThatThrownBy(() -> authService.googleLogin("auth-code", "http://localhost:5173/callback"))
                .isInstanceOf(CustomException.class)
                .extracting(e -> ((CustomException) e).getErrorCode())
                .isEqualTo(ErrorCode.GOOGLE_AUTH_FAILED);

        verify(userRepository, times(1)).save(any(User.class));
        verify(consentService, never()).recordSignupConsents(any(User.class), anyBoolean());
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
}
