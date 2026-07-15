package com.example.demo.user.controller;

import com.example.demo.user.dto.FindIdResponse;
import com.example.demo.user.dto.LoginRequest;
import com.example.demo.user.dto.SignupRequest;
import com.example.demo.user.dto.TokenResponse;
import com.example.demo.user.service.AuthService;
import com.example.demo.user.service.EmailVerificationService;
import com.example.demo.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AuthApiController.class)
@AutoConfigureMockMvc(addFilters = false)
class AuthApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private EmailVerificationService emailVerificationService;

    @Test
    void 아이디_중복확인_사용가능하면_200과_available_true를_반환한다() throws Exception {
        when(userService.checkLoginIdAvailable("newid")).thenReturn(true);

        mockMvc.perform(get("/auth/check-login-id").param("value", "newid"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.available").value(true));
    }

    @Test
    void 이메일_인증번호_발송은_200을_반환한다() throws Exception {
        mockMvc.perform(post("/auth/email/send-code")
                        .contentType("application/json")
                        .content("{\"email\":\"test@example.com\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void 회원가입_성공시_201을_반환한다() throws Exception {
        SignupRequest request = new SignupRequest();
        request.setLoginId("tester01");
        request.setEmail("tester01@example.com");
        request.setPassword("password1!");
        request.setName("테스터");

        mockMvc.perform(post("/auth/signup")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());
    }

    @Test
    void 회원가입_비밀번호가_형식에_맞지_않으면_400을_반환한다() throws Exception {
        SignupRequest request = new SignupRequest();
        request.setLoginId("tester01");
        request.setEmail("tester01@example.com");
        request.setPassword("password123");
        request.setName("테스터");

        mockMvc.perform(post("/auth/signup")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void 로그인_성공시_토큰을_반환한다() throws Exception {
        LoginRequest request = new LoginRequest();
        request.setLoginId("tester01");
        request.setPassword("password123");

        when(authService.login(any())).thenReturn(
                TokenResponse.builder().accessToken("access").refreshToken("refresh").build()
        );

        mockMvc.perform(post("/auth/login")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").value("access"))
                .andExpect(jsonPath("$.data.refreshToken").value("refresh"));
    }

    @Test
    void 로그아웃은_200을_반환한다() throws Exception {
        mockMvc.perform(post("/auth/logout")
                        .contentType("application/json")
                        .content("{\"refreshToken\":\"some-token\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void 아이디찾기_인증코드_발송은_200을_반환한다() throws Exception {
        mockMvc.perform(post("/auth/find-id/send-code")
                        .contentType("application/json")
                        .content("{\"email\":\"tester01@example.com\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void 아이디찾기_인증코드_확인은_마스킹된_아이디를_반환한다() throws Exception {
        LocalDateTime createdAt = LocalDateTime.of(2026, 1, 1, 12, 0);
        when(userService.findIdVerifyCode("tester01@example.com", "123456")).thenReturn(
                FindIdResponse.builder().maskedLoginId("te******").createdAt(createdAt).build()
        );

        mockMvc.perform(post("/auth/find-id/verify-code")
                        .contentType("application/json")
                        .content("{\"email\":\"tester01@example.com\",\"code\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.maskedLoginId").value("te******"));
    }

    @Test
    void 비밀번호찾기_인증코드_발송은_200을_반환한다() throws Exception {
        mockMvc.perform(post("/auth/password/send-code")
                        .contentType("application/json")
                        .content("{\"loginId\":\"tester01\",\"email\":\"tester01@example.com\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void 비밀번호찾기_인증코드_확인은_200을_반환한다() throws Exception {
        mockMvc.perform(post("/auth/password/verify-code")
                        .contentType("application/json")
                        .content("{\"loginId\":\"tester01\",\"email\":\"tester01@example.com\",\"code\":\"123456\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void 비밀번호_재설정_인증상태_조회는_verified_값을_반환한다() throws Exception {
        when(userService.isIdentityVerified("tester01")).thenReturn(true);

        mockMvc.perform(get("/auth/password/verification-status").param("loginId", "tester01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.verified").value(true));
    }

    @Test
    void 비밀번호_재설정은_200을_반환한다() throws Exception {
        mockMvc.perform(post("/auth/password/reset")
                        .contentType("application/json")
                        .content("{\"loginId\":\"tester01\",\"newPassword\":\"newPassword1!\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void 비밀번호_재설정_비밀번호가_형식에_맞지_않으면_400을_반환한다() throws Exception {
        mockMvc.perform(post("/auth/password/reset")
                        .contentType("application/json")
                        .content("{\"loginId\":\"tester01\",\"newPassword\":\"newPassword123\"}"))
                .andExpect(status().isBadRequest());
    }
}
