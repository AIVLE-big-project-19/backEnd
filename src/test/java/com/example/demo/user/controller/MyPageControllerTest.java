package com.example.demo.user.controller;

import com.example.demo.global.exception.CustomException;
import com.example.demo.global.exception.ErrorCode;
import com.example.demo.user.service.MyPageService;
import com.example.demo.user.service.WithdrawalService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(MyPageController.class)
@AutoConfigureMockMvc(addFilters = false)
class MyPageControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MyPageService myPageService;

    @MockitoBean
    private WithdrawalService withdrawalService;

    @BeforeEach
    void setUpAuth() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(1L, null, List.of())
        );
    }

    @AfterEach
    void clearAuth() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 탈퇴_성공시_200을_반환한다() throws Exception {
        mockMvc.perform(post("/users/me/withdrawal")
                        .contentType("application/json")
                        .content("{\"password\":\"password1!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("회원 탈퇴가 완료되었습니다."));

        verify(withdrawalService).withdraw(1L, "password1!");
    }

    @Test
    void 비밀번호가_틀리면_401을_반환한다() throws Exception {
        doThrow(new CustomException(ErrorCode.INVALID_CREDENTIALS))
                .when(withdrawalService).withdraw(1L, "wrong");

        mockMvc.perform(post("/users/me/withdrawal")
                        .contentType("application/json")
                        .content("{\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void 구글_계정은_password_없이도_탈퇴_요청이_가능하다() throws Exception {
        mockMvc.perform(post("/users/me/withdrawal")
                        .contentType("application/json")
                        .content("{}"))
                .andExpect(status().isOk());

        verify(withdrawalService).withdraw(1L, null);
    }
}
