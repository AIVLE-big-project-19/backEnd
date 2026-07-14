package com.example.demo.user.controller;

import com.example.demo.global.response.ApiResponse;
import com.example.demo.global.response.SuccessCode;
import com.example.demo.user.dto.*;
import com.example.demo.user.service.AuthService;
import com.example.demo.user.service.EmailVerificationService;
import com.example.demo.user.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthApiController {

    private final UserService userService;
    private final AuthService authService;
    private final EmailVerificationService emailVerificationService;

    @GetMapping("/check-login-id")
    public ApiResponse<AvailabilityResponse> checkLoginId(@RequestParam String value) {
        boolean available = userService.checkLoginIdAvailable(value);

        return ApiResponse.success(
                SuccessCode.USER_LOGIN_ID_CHECKED,
                AvailabilityResponse.builder().available(available).build()
        );
    }

    @PostMapping("/email/send-code")
    public ApiResponse<Void> sendEmailCode(@Valid @RequestBody EmailSendCodeRequest request) {
        emailVerificationService.sendCode(request.getEmail());

        return ApiResponse.success(SuccessCode.EMAIL_CODE_SENT);
    }

    @PostMapping("/email/verify-code")
    public ApiResponse<Void> verifyEmailCode(@Valid @RequestBody EmailVerifyCodeRequest request) {
        emailVerificationService.verifyCode(request.getEmail(), request.getCode());

        return ApiResponse.success(SuccessCode.EMAIL_CODE_VERIFIED);
    }

    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Void> signup(@Valid @RequestBody SignupRequest request) {
        userService.signup(request);

        return ApiResponse.success(SuccessCode.USER_SIGNUP);
    }

    @PostMapping("/login")
    public ApiResponse<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        TokenResponse response = authService.login(request);

        return ApiResponse.success(SuccessCode.USER_LOGIN, response);
    }

    @PostMapping("/token/refresh")
    public ApiResponse<TokenResponse> refresh(@Valid @RequestBody RefreshTokenRequest request) {
        TokenResponse response = authService.refresh(request.getRefreshToken());

        return ApiResponse.success(SuccessCode.TOKEN_REFRESHED, response);
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(@Valid @RequestBody RefreshTokenRequest request) {
        authService.logout(request.getRefreshToken());

        return ApiResponse.success(SuccessCode.USER_LOGOUT);
    }
}
