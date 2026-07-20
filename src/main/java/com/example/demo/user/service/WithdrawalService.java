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
