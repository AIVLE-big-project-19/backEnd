package com.example.demo.user.service;

import com.example.demo.global.exception.CustomException;
import com.example.demo.global.security.jwt.JwtProvider;
import com.example.demo.user.dto.LoginRequest;
import com.example.demo.user.dto.TokenResponse;
import com.example.demo.user.entity.Provider;
import com.example.demo.user.entity.Role;
import com.example.demo.user.entity.RefreshToken;
import com.example.demo.user.entity.User;
import com.example.demo.user.repository.RefreshTokenRepository;
import com.example.demo.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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

    private AuthService authService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        authService = new AuthService(userRepository, refreshTokenRepository, jwtProvider, passwordEncoder);
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

        assertThatThrownBy(() -> authService.login(request)).isInstanceOf(CustomException.class);
    }

    @Test
    void 비밀번호가_틀리면_예외가_발생한다() {
        LoginRequest request = new LoginRequest();
        request.setLoginId("tester01");
        request.setPassword("wrong-password");

        when(userRepository.findByLoginId("tester01")).thenReturn(Optional.of(sampleUser()));
        when(passwordEncoder.matches("wrong-password", "ENCODED")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(request)).isInstanceOf(CustomException.class);
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
        verify(refreshTokenRepository).save(any(RefreshToken.class));
    }

    @Test
    void refresh_토큰이_DB에_없으면_예외가_발생한다() {
        when(jwtProvider.validateToken("bad-token")).thenReturn(true);
        when(refreshTokenRepository.findByTokenHash(any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.refresh("bad-token")).isInstanceOf(CustomException.class);
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

        assertThatThrownBy(() -> authService.refresh("expired-token")).isInstanceOf(CustomException.class);
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
}
