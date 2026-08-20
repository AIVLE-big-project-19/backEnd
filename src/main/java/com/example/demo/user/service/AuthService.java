package com.example.demo.user.service;

import com.example.demo.global.exception.CustomException;
import com.example.demo.global.exception.ErrorCode;
import com.example.demo.global.security.jwt.JwtProvider;
import com.example.demo.global.util.EmailHasher;
import com.example.demo.global.util.HashUtil;
import com.example.demo.user.dto.LoginRequest;
import com.example.demo.user.dto.TokenResponse;
import com.example.demo.user.entity.Provider;
import com.example.demo.user.entity.RefreshToken;
import com.example.demo.user.entity.Role;
import com.example.demo.user.entity.User;
import com.example.demo.user.oauth.GoogleOAuthClient;
import com.example.demo.user.oauth.GoogleUserInfo;
import com.example.demo.user.repository.RefreshTokenRepository;
import com.example.demo.user.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtProvider jwtProvider;
    private final PasswordEncoder passwordEncoder;
    private final GoogleOAuthClient googleOAuthClient;
    private final ConsentService consentService;
    private final LoginAttemptService loginAttemptService;

    public AuthService(
            UserRepository userRepository,
            RefreshTokenRepository refreshTokenRepository,
            JwtProvider jwtProvider,
            PasswordEncoder passwordEncoder,
            GoogleOAuthClient googleOAuthClient,
            ConsentService consentService,
            LoginAttemptService loginAttemptService
    ) {
        this.userRepository = userRepository;
        this.refreshTokenRepository = refreshTokenRepository;
        this.jwtProvider = jwtProvider;
        this.passwordEncoder = passwordEncoder;
        this.googleOAuthClient = googleOAuthClient;
        this.consentService = consentService;
        this.loginAttemptService = loginAttemptService;
    }

    @Transactional
    public TokenResponse login(LoginRequest request) {
        loginAttemptService.checkNotLocked(request.getLoginId());

        User user = userRepository.findByLoginId(request.getLoginId())
                .orElseThrow(() -> new CustomException(ErrorCode.INVALID_CREDENTIALS));

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            loginAttemptService.recordFailure(request.getLoginId());
            throw new CustomException(ErrorCode.INVALID_CREDENTIALS);
        }

        loginAttemptService.recordSuccess(request.getLoginId());

        refreshTokenRepository.deleteByUserAndExpiresAtBefore(user, LocalDateTime.now());

        return issueTokens(user, request.isRememberMe());
    }

    @Transactional
    public TokenResponse googleLogin(String code, String redirectUri) {
        GoogleUserInfo googleUserInfo = googleOAuthClient.fetchUserInfo(code, redirectUri);

        User user = userRepository.findByEmailHash(EmailHasher.hash(googleUserInfo.getEmail())).orElse(null);

        if (user == null) {
            User newUser = User.builder()
                    .email(googleUserInfo.getEmail())
                    .emailHash(EmailHasher.hash(googleUserInfo.getEmail()))
                    .name(googleUserInfo.getName())
                    .provider(Provider.GOOGLE)
                    .providerId(googleUserInfo.getProviderId())
                    .role(Role.USER)
                    .build();
            try {
                user = userRepository.save(newUser);
                consentService.recordSignupConsents(user, false);
            } catch (DataIntegrityViolationException e) {
                // 참고: 동시 가입으로 이메일 고유성 충돌이 발생하면 rollback-only 트랜잭션을 복구하지 않고
                // 참고: 재시도 가능한 인증 오류를 반환해 다음 로그인에서 기존 사용자 경로를 사용하게 한다.
                throw new CustomException(ErrorCode.GOOGLE_AUTH_FAILED);
            }
        }

        if (user.getProvider() != Provider.GOOGLE) {
            throw new CustomException(ErrorCode.EMAIL_ALREADY_REGISTERED_AS_LOCAL);
        }

        refreshTokenRepository.deleteByUserAndExpiresAtBefore(user, LocalDateTime.now());

        return issueTokens(user, true);
    }

    @Transactional
    public TokenResponse refresh(String rawRefreshToken) {
        if (!jwtProvider.validateToken(rawRefreshToken)) {
            throw new CustomException(ErrorCode.INVALID_REFRESH_TOKEN);
        }

        String tokenHash = HashUtil.sha256(rawRefreshToken);
        RefreshToken saved = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new CustomException(ErrorCode.INVALID_REFRESH_TOKEN));

        if (saved.isExpired()) {
            refreshTokenRepository.delete(saved);
            throw new CustomException(ErrorCode.INVALID_REFRESH_TOKEN);
        }

        User user = saved.getUser();
        refreshTokenRepository.delete(saved);

        return issueTokens(user, saved.isRememberMe());
    }

    public void logout(String rawRefreshToken) {
        String tokenHash = HashUtil.sha256(rawRefreshToken);
        refreshTokenRepository.findByTokenHash(tokenHash)
                .ifPresent(refreshTokenRepository::delete);
    }

    private TokenResponse issueTokens(User user, boolean rememberMe) {
        String accessToken = jwtProvider.generateAccessToken(user.getId(), user.getRole());
        String refreshToken = jwtProvider.generateRefreshToken(user.getId());

        long validityMs = jwtProvider.getRefreshTokenValidityMs(rememberMe);
        LocalDateTime expiresAt = LocalDateTime.now().plus(Duration.ofMillis(validityMs));

        RefreshToken entity = RefreshToken.builder()
                .user(user)
                .tokenHash(HashUtil.sha256(refreshToken))
                .expiresAt(expiresAt)
                .rememberMe(rememberMe)
                .build();

        refreshTokenRepository.save(entity);

        return TokenResponse.builder()
                .accessToken(accessToken)
                .refreshToken(refreshToken)
                .build();
    }

    public TokenResponse adminTestLogin() {

        User user = userRepository.findByLoginId("admin")
                .orElseThrow(() -> new IllegalArgumentException("Admin 계정이 존재하지 않습니다. 먼저 생성해주세요."));


        return issueTokens(user, true);
    }
}
