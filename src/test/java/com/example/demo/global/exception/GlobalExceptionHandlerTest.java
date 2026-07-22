package com.example.demo.global.exception;

import com.example.demo.user.controller.AuthApiController;
import com.example.demo.user.dto.LoginRequest;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CustomException/MethodArgumentNotValidException 외의 예외도
 * {success, message, data} 포맷으로 응답하는지 검증한다. 이전에는 이 두 가지
 * 외의 예외(지원 안 하는 HTTP 메서드, 예상 못한 런타임 예외)가 스프링 기본
 * Whitelabel 에러 페이지(HTML)로 새어나가 프론트가 파싱할 수 없었다.
 */
@WebMvcTest(AuthApiController.class)
@AutoConfigureMockMvc(addFilters = false)
class GlobalExceptionHandlerTest {

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
    void 지원하지_않는_HTTP_메서드는_405와_JSON_포맷으로_응답한다() throws Exception {
        mockMvc.perform(get("/auth/login"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void 예상하지_못한_런타임_예외는_500과_JSON_포맷으로_응답하고_내부_메시지를_노출하지_않는다() throws Exception {
        LoginRequest request = new LoginRequest();
        request.setLoginId("tester01");
        request.setPassword("password123");

        when(authService.login(any())).thenThrow(new RuntimeException("DB 연결 실패: jdbc:mysql://internal-host"));

        mockMvc.perform(post("/auth/login")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("jdbc:mysql"))));
    }
}
