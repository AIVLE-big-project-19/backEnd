package com.example.demo.user.service;

import com.example.demo.global.exception.CustomException;
import com.example.demo.global.exception.ErrorCode;
import com.example.demo.user.dto.FindIdResponse;
import com.example.demo.user.dto.SignupRequest;
import com.example.demo.user.entity.Provider;
import com.example.demo.user.entity.Role;
import com.example.demo.user.entity.User;
import com.example.demo.user.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final EmailVerificationService emailVerificationService;
    private final PasswordEncoder passwordEncoder;

    public UserService(
            UserRepository userRepository,
            EmailVerificationService emailVerificationService,
            PasswordEncoder passwordEncoder
    ) {
        this.userRepository = userRepository;
        this.emailVerificationService = emailVerificationService;
        this.passwordEncoder = passwordEncoder;
    }

    public boolean checkLoginIdAvailable(String loginId) {
        return !userRepository.existsByLoginId(loginId);
    }

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

        userRepository.save(user);
        emailVerificationService.clearVerified(request.getEmail());
    }

    public void findIdSendCode(String email) {
        User user = userRepository.findByEmail(email).orElse(null);
        if (user == null || user.getLoginId() == null) {
            throw new CustomException(ErrorCode.USER_NOT_FOUND);
        }

        emailVerificationService.sendCode(email);
    }

    public FindIdResponse findIdVerifyCode(String email, String code) {
        emailVerificationService.verifyCodeOnly(email, code);

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new CustomException(ErrorCode.USER_NOT_FOUND));

        emailVerificationService.setIdentityVerified(user.getLoginId());

        return FindIdResponse.builder()
                .maskedLoginId(maskLoginId(user.getLoginId()))
                .createdAt(user.getCreatedAt())
                .build();
    }

    private String maskLoginId(String loginId) {
        int visibleLength = Math.min(2, loginId.length());
        String visible = loginId.substring(0, visibleLength);
        String masked = "*".repeat(loginId.length() - visibleLength);
        return visible + masked;
    }
}
