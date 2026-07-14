package com.example.demo.global.security.jwt;

import com.example.demo.user.entity.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JwtProviderTest {

    private JwtProvider jwtProvider;

    @BeforeEach
    void setUp() {
        jwtProvider = new JwtProvider(
                "test-only-secret-key-value-must-be-at-least-32-bytes-long",
                1_800_000L,
                86_400_000L,
                1_209_600_000L
        );
    }

    @Test
    void 액세스_토큰을_발급하고_검증할_수_있다() {
        String token = jwtProvider.generateAccessToken(1L, Role.USER);

        assertThat(jwtProvider.validateToken(token)).isTrue();
        assertThat(jwtProvider.getUserId(token)).isEqualTo(1L);
        assertThat(jwtProvider.getTokenType(token)).isEqualTo("ACCESS");
    }

    @Test
    void 액세스_토큰에서_ADMIN_역할을_추출할_수_있다() {
        String token = jwtProvider.generateAccessToken(1L, Role.ADMIN);

        assertThat(jwtProvider.getRole(token)).isEqualTo("ADMIN");
    }

    @Test
    void 액세스_토큰에서_USER_역할을_추출할_수_있다() {
        String token = jwtProvider.generateAccessToken(1L, Role.USER);

        assertThat(jwtProvider.getRole(token)).isEqualTo("USER");
    }

    @Test
    void 리프레시_토큰을_발급하고_검증할_수_있다() {
        String token = jwtProvider.generateRefreshToken(7L);

        assertThat(jwtProvider.validateToken(token)).isTrue();
        assertThat(jwtProvider.getUserId(token)).isEqualTo(7L);
        assertThat(jwtProvider.getTokenType(token)).isEqualTo("REFRESH");
    }

    @Test
    void 위조된_토큰은_검증에_실패한다() {
        String token = jwtProvider.generateAccessToken(1L, Role.USER);
        String tampered = token.substring(0, token.length() - 1) + "x";

        assertThat(jwtProvider.validateToken(tampered)).isFalse();
    }

    @Test
    void rememberMe_여부에_따라_리프레시_토큰_만료시간이_다르다() {
        assertThat(jwtProvider.getRefreshTokenValidityMs(true)).isEqualTo(1_209_600_000L);
        assertThat(jwtProvider.getRefreshTokenValidityMs(false)).isEqualTo(86_400_000L);
    }
}
